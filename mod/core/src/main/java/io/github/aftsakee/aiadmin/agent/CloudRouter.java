package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 云端值守路由（从 AgentService 拆出，1.0.0.34）：remote.cloud 开着且云端在线（长轮询心跳或推送订阅）时，聊天 / 事件 / 巡检进收件箱，
 * 并安排 handoff 秒后的兜底检查——那时还没被取走就交给本地 API；取走后一直没有动作的收回、超时的自动关闭（由服务的定时 tick 驱动）。
 * 也是 CloudTools 的后端：收件箱操作、为某条消息建动作上下文、开工说明、共享行动记录、宿主机接口、云端改设置后重读。
 * <ul>
 *   <li>锁：和 AgentService <b>共用同一把锁</b>（同 ChatIntake 的理由：判断要读服务的启停状态，兜底要回调服务的 submitLocal）。
 *       收件箱 CloudInbox 有自己的锁，从不回调这里或服务（锁顺序 服务 → 收件箱）。</li>
 *   <li>线程：offer 在调用 submit 的线程（服务端主线程或定时线程）上，handoff 在定时线程上，CloudTools 调用的方法在远程接口的请求线程上。
 *       开工说明要读提示词文件，锁外做。</li>
 * </ul>
 */
final class CloudRouter {
    static final String SNAPSHOT = "（云端值守：服务器概况请用 get_server_status、get_players_overview 等工具自己查）";

    /** 服务提供给路由的几样东西。 */
    interface Owner {
        /** 自动运行开着（停用时走本地路径，run 里会直接忽略）。 */
        boolean enabled();

        /** 直接交给本地代理线程；必须在共用锁内调用。 */
        boolean submitLocal(Trigger t);

        String activeProvider();

        /** 调度定时器；服务还没启动时为 null。 */
        Scheduler timer();
    }

    private final Object lock;
    private final Owner owner;
    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final AgentService.Host host;
    private final JsonlLog log;
    private final Memory memory;
    private final Prompts prompts;
    private final PersonaSettings persona;
    private final PlayerPrefs prefs;
    private final ProjectStore projects;
    private final Clock clock;
    private final CloudInbox inbox;
    private volatile AgentService.CloudPush push;
    /** 云端 server_power 用的宿主机启停程序；测试里换成假的。 */
    private volatile ServerPower.Runner serverPower = ServerPower.sudo();
    /** 平台层的"重读设置"（/aibot reload 同一个方法），返回 null = 成功，否则是原因。 */
    private volatile Supplier<String> reloader;

    CloudRouter(Object lock, Owner owner, Supplier<BotConfig> cfg, Supplier<Rules> rules, AgentService.Host host, JsonlLog log, Memory memory,
                Prompts prompts, PersonaSettings persona, PlayerPrefs prefs, ProjectStore projects, Clock clock) {
        this.lock = lock;
        this.owner = owner;
        this.cfg = cfg;
        this.rules = rules;
        this.host = host;
        this.log = log;
        this.memory = memory;
        this.prompts = prompts;
        this.persona = persona;
        this.prefs = prefs;
        this.projects = projects;
        this.clock = clock;
        this.inbox = new CloudInbox(clock::millis);
    }

    // ———————————————————— 路由 ————————————————————

    /** 这个触发要不要交给云端：remote.cloud 开着（且远程接口开着、允许动作）、在 route 里、云端此刻在线（长轮询或推送订阅）。 */
    boolean wants(Trigger.Kind k) {
        BotConfig.Remote r = cfg.get().remote;
        if (!r.cloud.enabled || !r.enabled || !r.allowActions || !owner.enabled()) return false;
        String key = switch (k) {
            case CHAT -> "chat";
            case EVENT -> "event";
            case PATROL -> "patrol";
            default -> null;
        };
        // 锁顺序：服务 → 收件箱（收件箱内部从不回调服务，所以调用方持着服务锁也不会死锁）
        return key != null && r.cloud.route.contains(key) && (inbox.online(r.cloud.onlineTimeoutSeconds * 1000L) || pushActive());
    }

    /** 放进收件箱并安排兜底；收件箱满了返回 false（调用方改走本地）。在共用锁内调用。 */
    boolean offer(Trigger t, String chattiness) {
        BotConfig c = cfg.get();
        CloudInbox.Item it = inbox.offer(t, Prompts.user(t, c, SNAPSHOT, "", List.of(), chattiness) + Prompts.social(t, prefs, projects), c.remote.cloud.maxPending);
        if (it == null) {
            log.warn("agent", "cloud.full", "云端收件箱已满，改由本地处理：" + t.label(), null, null);
            return false;
        }
        log.info("agent", "cloud.offer", "交给云端：" + t.label(), null, JsonlLog.data("item", it.id, "events", t.events()));
        AgentService.CloudPush p = push;
        if (p != null) p.publish(it, host.onlinePlayers()); // 推送通道：摘要发到云端的回调地址（异步，失败不影响收件箱）
        Scheduler timer = owner.timer();
        if (timer != null && !timer.isShutdown()) {
            int sec = c.remote.cloud.handoffSeconds;
            timer.schedule(() -> handoff(it, sec), sec * 1000L);
        }
        return true;
    }

    /**
     * 兜底：到点还没被云端取走就交给本地（只交一次）。推送已送达的条目再多等到 webhook_handoff_seconds（从放入算起）：
     * 云端代理被叫醒、读上下文、再调用 wait_events 往往要一两分钟，不能按长轮询的 handoff 秒数就收走。
     */
    private void handoff(CloudInbox.Item it, int sec) {
        if (it.delivered() > 0 && it.state() == CloudInbox.State.PENDING) {
            long limit = it.created + cfg.get().remote.cloud.webhookHandoffSeconds * 1000L;
            long left = limit - clock.millis();
            Scheduler timer = owner.timer();
            if (left > 0 && timer != null && !timer.isShutdown()) {
                int total = sec + (int) Math.ceil(left / 1000.0);
                timer.schedule(() -> handoff(it, total), left);
                return;
            }
        }
        if (!inbox.claimForLocal(it.id)) return;
        log.info("agent", "cloud.handoff", "云端 " + sec + " 秒内没取走，改由本地处理：" + it.trigger.label(), null, JsonlLog.data("item", it.id));
        synchronized (lock) {
            owner.submitLocal(it.trigger);
        }
    }

    /** 定时 tick（每 30 秒）：收回取走后一直没动作的、关闭超时没 complete 的。锁外调用。 */
    void tick(BotConfig c) {
        // 取走后 2×handoff 秒内一个动作都没有（多半是客户端已经断开、回应没送到）：收回交给本地
        for (CloudInbox.Item it : inbox.reclaimIdle(c.remote.cloud.handoffSeconds * 2000L)) {
            log.info("agent", "cloud.reclaim", "云端取走后一直没有动作，改由本地处理：" + it.trigger.label(), null, JsonlLog.data("item", it.id));
            synchronized (lock) {
                owner.submitLocal(it.trigger);
            }
        }
        for (CloudInbox.Item it : inbox.expire(c.remote.cloud.autoCloseMinutes * 60_000L)) {
            record(it, "（云端取走后 " + c.remote.cloud.autoCloseMinutes + " 分钟没有调用 complete_item，自动关闭）", true);
        }
    }

    // ———————————————————— 在线状态 ————————————————————

    void push(AgentService.CloudPush p) {
        push = p;
    }

    /** 推送被云端确认收到（McpEvents 回调）：这条消息的兜底计时放宽到 webhook_handoff_seconds。 */
    void delivered(String itemId) {
        inbox.markDelivered(itemId);
    }

    /** 云端带着 item_id 做查询：刷新这条消息的活动时间（见 CloudInbox.touch）。 */
    boolean touch(String itemId) {
        return inbox.touch(itemId);
    }

    /** 远程的云端值守工具每次调用都算心跳。 */
    void heartbeat() {
        inbox.heartbeat();
    }

    /** 关服时让挂着的 wait_events 立即返回。 */
    void wakeAll() {
        inbox.wakeAll();
    }

    private boolean pushActive() {
        AgentService.CloudPush p = push;
        return p != null && p.active();
    }

    String statusLine() {
        BotConfig.Cloud cl = cfg.get().remote.cloud;
        if (!cl.enabled) return "云端值守：关闭";
        Map<CloudInbox.State, Integer> n = inbox.counts();
        long seen = inbox.lastSeen();
        boolean pushing = pushActive();
        boolean on = inbox.online(cl.onlineTimeoutSeconds * 1000L) || pushing;
        AgentService.CloudPush p = push;
        String ps = p == null ? "" : p.status();
        return "云端值守：" + (on ? (pushing ? "在线（推送）" : "在线") : "离线（本地处理）") + (seen > 0 ? "，最近一次 " + Text.time(seen).substring(11) : "，从未连接")
                + (ps.isEmpty() ? "" : "；" + ps)
                + "；收件箱 待取 " + n.get(CloudInbox.State.PENDING) + "、处理中 " + n.get(CloudInbox.State.TAKEN)
                + "、已完成 " + n.get(CloudInbox.State.DONE) + "、转本地 " + n.get(CloudInbox.State.FALLBACK) + "、超时 " + n.get(CloudInbox.State.AUTO_CLOSED);
    }

    // ———————————————————— 给 CloudTools 用 ————————————————————

    CloudInbox inbox() {
        return inbox;
    }

    /**
     * 为某条收件箱消息执行动作时用的上下文（第一次动作时建立，之后复用：per-run 上限、恶作剧档位、动作记录都挂在上面）。
     * 返回 null = 这条已不是 TAKEN（完成、自动关闭或已收回交本地），不能再为它执行动作。
     * 预设模式和档位先取好（预设自己的锁），再交给收件箱在它自己的锁里"检查状态 + 建上下文"（见 CloudInbox.beginAction 的锁顺序约束）。
     */
    RunContext context(String itemId) {
        PersonaSettings.Snapshot s = persona.snapshot();
        BotConfig c = cfg.get();
        Rules r = rules.get();
        return inbox.beginAction(itemId, it -> {
            RunContext ctx = new RunContext("cloud-" + it.id, it.trigger, c, r, "cloud");
            ctx.persona(s.mode(), s.level());
            return ctx;
        });
    }

    /** 云端处理完（或自动关闭）：写进共享的行动记录，下次本地或云端运行都能看到。 */
    void record(CloudInbox.Item it, String summary, boolean auto) {
        RunContext ctx = it.ctx();
        List<String> acts = ctx == null ? List.of() : ctx.actions();
        memory.add(new Memory.Entry(Text.now(), "云端·" + it.trigger.label(), "cloud", Text.brief(summary == null ? "" : summary, 400),
                acts, auto ? "未调用 complete_item，已自动关闭" : null));
        log.info("agent", auto ? "cloud.auto_closed" : "cloud.done", "云端处理" + (auto ? "超时自动关闭" : "完成") + "：" + it.trigger.label(), "cloud-" + it.id,
                JsonlLog.data("item", it.id, "summary", Text.brief(summary, 300), "actions", acts));
    }

    /** 云端开工时读一次：和本地运行相同的系统提示词（当前接入点的文件 + 服主补充规则）+ 当前预设模式。远程线程调用，读文件在锁外。 */
    String briefing() {
        BotConfig c = cfg.get();
        String p = owner.activeProvider();
        PersonaSettings.Snapshot s = persona.snapshot();
        ProviderConfig pc = c.providers.get(p);
        String sys = pc == null ? Prompts.strip(Prompts.DEFAULT_SYSTEM) : prompts.system(p, pc, c, host.serverLabel());
        String pp = persona.prompt(c, s);
        return sys + (pp.isEmpty() ? "" : "\n\n" + pp);
    }

    BotConfig config() {
        return cfg.get();
    }

    Rules rules() {
        return rules.get();
    }

    Memory memory() {
        return memory;
    }

    JsonlLog log() {
        return log;
    }

    int onlinePlayers() {
        return host.onlinePlayers();
    }

    Path configFile() {
        return prompts.configDir().resolve("config.json");
    }

    ServerPower.Runner serverPower() {
        return serverPower;
    }

    void serverPower(ServerPower.Runner r) {
        serverPower = r;
    }

    void reloader(Supplier<String> r) {
        reloader = r;
    }

    /**
     * 云端改完设置后重读：放到独立线程、稍等一秒再做——调用方正在远程接口的请求线程里，reload 会动远程接口，
     * 得先让这次回应发出去。没有 reloader（测试）时什么都不做。
     */
    void reloadSoon(String why) {
        Supplier<String> r = reloader;
        if (r == null) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
                String err = r.get();
                if (err != null) log.warn("agent", "settings.reload_failed", "云端改设置后重读失败（" + why + "）：" + err, null, null);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("agent", "settings.reload_failed", "云端改设置后重读失败（" + why + "）：" + e, null, null);
            }
        }, "aiadmin-settings-reload");
        t.setDaemon(true);
        t.start();
    }
}
