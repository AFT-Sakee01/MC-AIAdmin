package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.llm.Providers;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 一次运行（从 AgentService 拆出，1.0.0.34）：运行前检查 → 选接入点 → 依次尝试（失败换备用）→ 记账、记行动、计连续失败 → 压缩长期记忆。
 * <ul>
 *   <li>线程：只在 AgentService 的单个代理线程上执行（同一时间只跑一次运行，避免两次运行交错执行指令，也限制了花费）。</li>
 *   <li>锁：和 AgentService <b>共用同一把锁</b>，只在读写"当前任务 / 累计次数"和读服务的锁定状态时短暂持有；
 *       模型请求、余额判断、读提示词文件、写记忆都在锁外。额度（Quota）、启停（ScheduleGate）、余额（BalanceMonitor）、
 *       预设（PersonaSettings）各有自己的锁，从不回调服务。</li>
 *   <li>时间：额度时段、连续失败暂停这些业务时间用注入的时钟；运行耗时和超时（limits.run_timeout_seconds）是对网络请求的真实限时，
 *       用系统时钟（AgentRunner 也按系统时钟判断）。</li>
 *   <li>已经执行过动作的运行不换接入点重来（会重复执行指令）。</li>
 * </ul>
 */
final class RunExecutor {
    private static final DateTimeFormatter CID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 服务提供给运行的几样东西。 */
    interface Owner {
        /** 设置文件读取失败、机器人锁定中。 */
        boolean locked();

        boolean enabled();

        String activeProvider();

        /** 发额度 / 失败提醒（日志 + 在线 OP），锁外调用。 */
        void alert(List<String> alerts, Rules rl);
    }

    private final Object lock;
    private final Owner owner;
    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final AgentService.Host host;
    private final Tools tools;
    private final HttpTransport http;
    private final JsonlLog log;
    private final Memory memory;
    private final Quota quota;
    private final Prompts prompts;
    private final PersonaSettings persona;
    private final ScheduleGate gate;
    private final BalanceMonitor balance;
    private final PlayerPrefs prefs;
    private final ProjectStore projects;
    private final Clock clock;
    private final Runnable onRunFinished;

    /** 正在跑的运行（cancel 用）和本次开服累计次数；共用锁保护。 */
    private RunContext current;
    private int runsTotal;

    /** 各接入点依次尝试的结果。result 为 null = 全部失败，lastError 是最后一个错误。 */
    private record Attempt(AgentRunner.Result result, String provider, String lastError, RunContext ctx, long ms) {}

    RunExecutor(Object lock, Owner owner, Supplier<BotConfig> cfg, Supplier<Rules> rules, AgentService.Host host, Tools tools, HttpTransport http,
                JsonlLog log, Memory memory, Quota quota, Prompts prompts, PersonaSettings persona, ScheduleGate gate, BalanceMonitor balance,
                PlayerPrefs prefs, ProjectStore projects, Clock clock, Runnable onRunFinished) {
        this.lock = lock;
        this.owner = owner;
        this.cfg = cfg;
        this.rules = rules;
        this.host = host;
        this.tools = tools;
        this.http = http;
        this.log = log;
        this.memory = memory;
        this.quota = quota;
        this.prompts = prompts;
        this.persona = persona;
        this.gate = gate;
        this.balance = balance;
        this.prefs = prefs;
        this.projects = projects;
        this.clock = clock;
        this.onRunFinished = onRunFinished;
    }

    /** 当前任务（没有为 null）。调用方持共用锁。 */
    RunContext current() {
        return current;
    }

    /** 本次开服累计运行次数。调用方持共用锁。 */
    int runsTotal() {
        return runsTotal;
    }

    /** 代理线程的入口：任何异常都不能让代理线程死掉。 */
    void runSafely(Trigger t) {
        try {
            run(t);
        } catch (Throwable e) {
            log.unhandled("agent", "run.crash", "运行时出现未处理异常", null, e); // 全局规则：未处理异常每次都写，不排重
            if (t.reply() != null) t.reply().accept("出错了：" + e);
        } finally {
            synchronized (lock) {
                current = null;
            }
            if (onRunFinished != null) onRunFinished.run();
        }
    }

    private void run(Trigger t) {
        BotConfig c = cfg.get();
        Rules rl = rules.get();
        boolean admin = t.kind() == Trigger.Kind.ADMIN;
        LocalDateTime startAt = LocalDateTime.now(clock);
        if (!admit(t, rl, admin, startAt)) return;
        String cid = "run-" + startAt.format(CID) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        List<String> providers = providers(t, c, cid);
        if (providers.isEmpty()) return;
        quota.recordStart(rl.quota, admin, startAt);
        Attempt a = attempt(t, c, rl, cid, providers);
        settle(t, c, rl, admin, cid, a);
    }

    /** 运行前检查：锁定、停用（管理员提问不受停用影响）、额度、启停。不通过时回复原因并返回 false。 */
    private boolean admit(Trigger t, Rules rl, boolean admin, LocalDateTime startAt) {
        if (owner.locked()) {
            if (t.reply() != null) t.reply().accept("拒绝：设置文件有误，机器人已锁定");
            return false;
        }
        if (!admin && !owner.enabled()) return false;
        String blocked = quota.blocked(rl.quota, admin, startAt);
        if (blocked == null) blocked = gate.block(rl, admin);
        if (blocked != null) {
            log.info("agent", "run.skipped", "跳过：" + t.label() + "（" + blocked + "）", null, JsonlLog.data("reason", blocked));
            if (t.reply() != null) t.reply().accept("拒绝：" + blocked);
            return false;
        }
        return true;
    }

    /** 依次尝试的接入点：指定的（管理员提问可指定）或当前的 + 备用，去重、去掉不存在的和余额不足停用的。都不可用时回复并返回空列表。 */
    private List<String> providers(Trigger t, BotConfig c, String cid) {
        List<String> chain = new ArrayList<>();
        chain.add(t.provider() != null ? t.provider() : owner.activeProvider());
        if (t.provider() == null) chain.addAll(c.fallbackProviders);
        List<String> providers = new ArrayList<>(new LinkedHashSet<>(chain));
        providers.removeIf(p -> !c.providers.containsKey(p));
        List<String> stopped = new ArrayList<>();
        for (String p : providers) if (balance.stopped(p)) stopped.add(p);
        providers.removeAll(stopped);
        if (providers.isEmpty()) {
            String msg = "没有可用的接入点（余额不足已停用：" + String.join("、", stopped) + "）。充值后用 /aibot quota 刷新";
            log.warn("agent", "run.skipped", msg, cid, null);
            if (t.reply() != null) t.reply().accept(msg);
        }
        return providers;
    }

    /** 依次尝试各接入点，第一个成功就停；已经执行过动作或被取消也停（不能换接入点重来）。 */
    private Attempt attempt(Trigger t, BotConfig c, Rules rl, String cid, List<String> providers) {
        long t0 = System.currentTimeMillis();
        String snapshot;
        try {
            snapshot = host.snapshotJson();
        } catch (RuntimeException e) {
            snapshot = "{\"error\":\"获取概况失败：" + e.getClass().getSimpleName() + "\"}";
        }
        String user = Prompts.user(t, c, snapshot, memory.digest(), memory.recent(c.limits.memoryEntries), persona.chattiness())
                + Prompts.social(t, prefs, projects);
        Compressor comp = new Compressor(c.compression, prompts, this::summarize);
        long deadline = t0 + c.limits.runTimeoutSeconds * 1000L;

        AgentRunner.Result result = null;
        String usedProvider = null;
        String lastError = null;
        RunContext ctx = null;
        for (String p : providers) {
            ProviderConfig pc = c.providers.get(p);
            ctx = new RunContext(cid, t, c, rl, p);
            PersonaSettings.Snapshot ps = persona.snapshot();
            ctx.persona(ps.mode(), ps.level());
            synchronized (lock) {
                current = ctx;
            }
            log.info("agent", "run.start", "开始运行：" + t.label() + "（" + p + " / " + pc.model + "）", cid,
                    JsonlLog.data("trigger", t.kind().name(), "provider", p, "model", pc.model, "events", t.events(), "prompt", t.prompt()));
            try {
                String system = prompts.system(p, pc, c, host.serverLabel());
                String pp = persona.prompt(c, ps);
                if (!pp.isEmpty()) system += "\n\n" + pp;
                List<Llm.ToolSpec> specs = tools.specs(rl.permissions.hiddenTools());
                result = AgentRunner.run(() -> Providers.open(pc, specs, http), system, user, tools, ctx, log, deadline, comp);
                usedProvider = p;
                break;
            } catch (LlmException | RuntimeException e) {
                // RuntimeException：base_url 写错（URI 非法）、回复结构异常等。按失败处理：照常记账、记行动、计连续失败
                int status = e instanceof LlmException le ? le.status() : -1;
                lastError = p + "：" + (e instanceof LlmException ? e.getMessage() : e.getClass().getSimpleName() + " " + e.getMessage());
                log.error("agent", "llm.error", "接入点请求失败：" + p, cid, JsonlLog.data("provider", p, "status", status, "error", Text.brief(lastError, 400)));
                if (ctx.acted() || ctx.cancelled()) break; // 已经执行过动作就不能换接入点重来
            }
        }
        return new Attempt(result, usedProvider, lastError, ctx, System.currentTimeMillis() - t0);
    }

    /** 结算：累计次数、连续失败、记账、行动记录、回复提问者、压缩记忆、额度提醒。 */
    private void settle(Trigger t, BotConfig c, Rules rl, boolean admin, String cid, Attempt a) {
        synchronized (lock) {
            runsTotal++;
        }
        String pause = gate.recordRun(a.result() != null, rl.schedule, a.lastError());
        if (pause != null) owner.alert(List.of(pause), rl);
        RunContext ctx = a.ctx();
        if (a.result() == null) {
            if (ctx != null && ctx.extraTokens() > 0) quota.recordUsage(rl.quota, admin, ctx.extraTokens(), ctx.extraCost(), LocalDateTime.now(clock));
            memory.add(new Memory.Entry(Text.now(), t.label(), null, "", ctx == null ? List.of() : ctx.actions(), a.lastError()));
            log.error("agent", "run.failed", "运行失败：" + t.label(), cid, JsonlLog.data("error", a.lastError(), "ms", a.ms()));
            if (t.reply() != null) t.reply().accept("失败：" + a.lastError());
        } else {
            AgentRunner.Result result = a.result();
            ProviderConfig used = c.providers.get(a.provider());
            double cost = cost(used, result.usage()) + ctx.extraCost(); // extra = 压缩摘要调用（可能是别的接入点）
            long tokens = result.usage().total() + ctx.extraTokens();
            quota.recordUsage(rl.quota, admin, tokens, cost, LocalDateTime.now(clock));
            memory.add(new Memory.Entry(Text.now(), t.label(), a.provider(), Text.brief(result.text(), 400), ctx.actions(), result.error()));
            log.info("agent", "run.end", "运行结束：" + Text.brief(result.text(), 300), cid,
                    JsonlLog.data("provider", a.provider(), "steps", result.steps(), "ms", a.ms(), "input_tokens", result.usage().input(),
                            "output_tokens", result.usage().output(), "extra_tokens", ctx.extraTokens(), "estimated_cost", cost,
                            "currency", used.currency, "compressions", result.compressions(), "actions", ctx.actions(), "error", result.error()));
            if (t.reply() != null) {
                String text = result.text().isBlank() ? "（没有文字回复）" : result.text().trim();
                if (result.error() != null) text += "\n（中止：" + result.error() + "）";
                t.reply().accept(text);
            }
        }
        compressMemory(c);
        owner.alert(quota.newAlerts(rl.quota, LocalDateTime.now(clock)), rl);
    }

    private static double cost(ProviderConfig p, Llm.Usage u) {
        return (u.input() * p.priceInputPerMillion + u.output() * p.priceOutputPerMillion) / 1_000_000.0;
    }

    /** 无工具的一次性调用（压缩摘要用）。token 和花费记到 ctx 上，由运行结束时统一记账；ctx 为 null 时直接记账。 */
    private String summarize(String provider, String instructions, String material, RunContext ctx) throws LlmException {
        BotConfig c = cfg.get();
        ProviderConfig pc = c.providers.get(provider);
        if (pc == null) throw new LlmException("没有这个接入点：" + provider, 0);
        Llm.Reply r = Providers.open(pc, List.of(), http).start(instructions, material);
        double cost = cost(pc, r.usage());
        if (ctx != null) ctx.addExtra(r.usage().total(), cost);
        else quota.recordUsage(rules.get().quota, false, r.usage().total(), cost, LocalDateTime.now(clock));
        return r.text();
    }

    /** 行动记录太多时，把较旧的合并进长期记忆摘要（运行结束后在代理线程里做）。 */
    private void compressMemory(BotConfig c) {
        BotConfig.MemoryCompression mc = c.compression.memory;
        if (!c.compression.enabled || !mc.enabled) return;
        List<Memory.Entry> old = memory.toCompress(mc.compressWhenOver, mc.keepRecent);
        if (old.isEmpty()) return;
        StringBuilder lines = new StringBuilder();
        for (Memory.Entry e : old) {
            lines.append("- [").append(e.time()).append("] ").append(e.trigger()).append("：").append(Text.brief(e.summary(), 200));
            if (e.actions() != null && !e.actions().isEmpty()) lines.append("；动作：").append(Text.brief(String.join("；", e.actions()), 200));
            if (e.error() != null) lines.append("；出错：").append(Text.brief(e.error(), 80));
            lines.append('\n');
        }
        String prev = memory.digest();
        String digest = null;
        String provider = owner.activeProvider();
        if (mc.type.equals("summarize") && !balance.stopped(provider)) {
            try {
                String instructions = prompts.template(mc.promptFile, Prompts.DEFAULT_MEMORY_COMPRESS).replace("{max_chars}", Integer.toString(mc.summaryMaxChars));
                String material = "之前的长期记忆摘要：\n" + (prev.isBlank() ? "（无）" : prev) + "\n\n需要合并的旧记录：\n" + lines;
                String s = summarize(provider, instructions, material, null);
                if (s != null && !s.isBlank()) digest = Text.clip(s.trim(), mc.summaryMaxChars);
            } catch (LlmException | RuntimeException e) {
                log.warn("agent", "memory.compress_failed", "长期记忆模型摘要失败，改用机械合并：" + e.getMessage(), null, null);
            }
        }
        if (digest == null) {
            // 机械合并：旧摘要 + 旧记录，超长时保留最新的部分
            String merged = (prev.isBlank() ? "" : prev + "\n") + lines.toString().trim();
            digest = merged.length() <= mc.summaryMaxChars ? merged : "…" + merged.substring(merged.length() - mc.summaryMaxChars);
        }
        memory.applyCompression(old, digest);
        log.info("agent", "memory.compressed", "长期记忆压缩：合并 " + old.size() + " 条旧记录", null,
                JsonlLog.data("merged", old.size(), "digest_chars", digest.length(), "method", mc.type));
    }
}
