package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.Balance;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.JsonFiles;
import io.github.aftsakee.aiadmin.util.Text;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 调度层的门面：生命周期、触发入口（管理员提问、巡检、事件、聊天）、提交（云端优先、本地兜底）和管理命令。
 * 具体工作分给几个部件（1.0.0.33–34 从本类拆出）：
 * <ul>
 *   <li>{@link ChatIntake}：公屏 / 私聊收集、冷却与上限、合并；{@link CloudRouter}：云端收件箱路由与兜底，CloudTools 的后端；</li>
 *   <li>{@link RunExecutor}：一次运行（选接入点、尝试、记账、压缩记忆）；{@link ScheduleGate}：启停限制与连续失败暂停；</li>
 *   <li>{@link PersonaSettings}：预设模式 / 恶作剧档位 / 话量；{@link BalanceMonitor}：接入点余额。</li>
 * </ul>
 * 并发约束：
 * <ul>
 *   <li>同一时间只跑一次运行（单线程执行器），避免两次运行交错执行指令，也限制了花费。等待队列有上限（limits.queue_max）；
 *       满了丢弃新的自动任务，管理员提问会收到"忙"的提示。</li>
 *   <li>额度（quota.windows）在运行真正开始时检查并 +1 次，结束时累加 token 和估算花费；用完后自动运行暂停到时段结束。</li>
 *   <li>事件按"类型 + 对象（玩家名）"冷却，冷却期内的事件不唤醒；debounce 秒内的事件合并成一次唤醒。</li>
 *   <li>锁：本对象的锁同时保护 ChatIntake、CloudRouter、RunExecutor 的状态（它们和本类互相调用，共用一把锁才没有锁顺序反转）。
 *       ScheduleGate、PersonaSettings、BalanceMonitor、CloudInbox、Quota 和各状态仓库各有自己的锁，持锁时从不回调本类——
 *       锁顺序只有一个方向：本对象 → 它们。网络请求（模型、余额）和读提示词文件一律在本对象的锁外；锁内只允许"投递"类调用
 *       （host.tellPlayer、推送 publish 都只是把任务交给主线程 / 推送线程就返回）。</li>
 *   <li>时间：业务时间（冷却、上限窗口、额度时段、暂停、收件箱）取自注入的 {@link Clock}，延时任务交给注入的 {@link Scheduler}；
 *       测试可以换成假时钟和手动推进的定时器，不用真睡。</li>
 * </ul>
 */
public final class AgentService {
    public static final String EV_JOIN = "player_join";
    public static final String EV_LEAVE = "player_leave";
    public static final String EV_DEATH = "player_death";
    public static final String EV_ADVANCEMENT = "advancement";
    public static final String EV_TPS = "tps_low";
    public static final String EV_ERROR = "server_error";
    public static final String EV_CHAT = "chat_keyword";
    /** 有意义的行为：首次造出机器、捡到稀有物品、驯服、击败 Boss、救下队友、工程进展。subject 写成"玩家|类别"，各类别分开冷却。 */
    public static final String EV_MILESTONE = "milestone";
    /** 死亡之后：回去捡回了东西，或者没捡回（掉落物消失）。 */
    public static final String EV_DEATH_FOLLOWUP = "death_followup";
    public static final List<String> TALK_LEVELS = PersonaSettings.TALK_LEVELS;
    /** 远程接口 allow_actions=false 时要隐藏的云端工具。 */
    public static final List<String> CLOUD_ACTION_TOOLS = CloudTools.ACTIONS;
    public static final String CLOUD_RUN_FOR = CloudTools.RUN_FOR;

    /** 平台层提供的服务器信息。snapshotJson 可能要切到服务端主线程，会阻塞调用者一小会。 */
    public interface Host {
        int onlinePlayers();

        String snapshotJson();

        String serverLabel();

        /** 额度/余额提醒：写后台；inGame = 同时发给在线 OP。可在任何线程调用。 */
        void alertAdmins(String message, boolean inGame);

        /** 给一名在线玩家发一条系统提示（不经过模型，例如"别说了"的确认）。可在任何线程调用，不阻塞。 */
        default void tellPlayer(String player, String message) {}
    }

    /** 推送通道（远程接口的 MCP Events）：有能投递的订阅就算云端在线，新消息进收件箱时推一份摘要过去。 */
    public interface CloudPush {
        boolean active();

        void publish(CloudInbox.Item it, int onlinePlayers);

        /** 状态行用的一小段，没有订阅返回空串。 */
        String status();
    }

    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final Host host;
    private final JsonlLog log;
    private final Memory memory;
    private final Quota quota;
    private final Clock clock;
    private final Supplier<Scheduler> timers;

    private final PlayerPrefs prefs;
    private final CombatLog combat = new CombatLog();
    private final Milestones milestones;
    private final ProjectStore projects;
    private final BuildLedger ledger;
    private final CommandNotes commandNotes;

    private final ScheduleGate gate;
    private final PersonaSettings persona;
    private final BalanceMonitor balance;
    private final ChatIntake chat;
    private final CloudRouter router;
    private final RunExecutor runner;
    /** 只给远程接口用的云端工具（wait_events、reply_chat…）；本地代理看不到。 */
    private final Tools cloudTools;

    private ThreadPoolExecutor worker;
    private Scheduler timer;
    private boolean enabledOverride = true;
    private String providerOverride;
    /** 非 null = 设置文件读取失败，机器人锁定：任何运行（包括管理员提问）都拒绝，直到 reload 成功。 */
    private String lockReason;
    private long lastPatrol;
    private long lastLedgerSave;
    private final Map<String, Long> cooldown = new HashMap<>();
    private final List<String> pendingEvents = new ArrayList<>();
    private boolean eventFlushPending;

    public AgentService(Supplier<BotConfig> cfg, Supplier<Rules> rules, Host host, Tools tools, HttpTransport http, JsonlLog log, Path stateDir,
                        Prompts prompts, Runnable onRunFinished) {
        this(cfg, rules, host, tools, http, log, stateDir, prompts, onRunFinished, Clock.systemDefaultZone(), () -> Scheduler.threaded("aiadmin-timer"));
    }

    /** clock：业务时间；timers：start() 时创建定时器（测试传假时钟和手动推进的定时器）。 */
    public AgentService(Supplier<BotConfig> cfg, Supplier<Rules> rules, Host host, Tools tools, HttpTransport http, JsonlLog log, Path stateDir,
                        Prompts prompts, Runnable onRunFinished, Clock clock, Supplier<Scheduler> timers) {
        this.cfg = cfg;
        this.rules = rules;
        this.host = host;
        this.log = log;
        this.clock = clock;
        this.timers = timers;
        // 状态文件读坏（已改名隔离）、写失败都记进错误日志；必须在创建各状态对象之前接上
        JsonFiles.setErrorSink((event, msg) -> log.error("state", event, msg, null, JsonlLog.data()));
        this.memory = new Memory(stateDir.resolve("memory.json"));
        this.quota = new Quota(stateDir.resolve("quota.json"));
        if (quota.loadError() != null) {
            log.warn("agent", "quota.load_failed", quota.loadError(), null, null);
            host.alertAdmins(quota.loadError(), true);
        }
        if (memory.loadError() != null) log.warn("agent", "memory.load_failed", memory.loadError(), null, null);
        this.prefs = new PlayerPrefs(stateDir.resolve("player-prefs.json"), clock::millis);
        if (prefs.loadError() != null) log.warn("agent", "prefs.load_failed", prefs.loadError(), null, null);
        this.milestones = new Milestones(stateDir.resolve("milestones.json"));
        this.projects = new ProjectStore(stateDir.resolve("projects.json"), clock::millis);
        this.ledger = new BuildLedger(stateDir.resolve("build-ledger.json"), clock::millis);
        this.commandNotes = new CommandNotes(stateDir.resolve("command-notes.json"), clock::millis);
        for (String err : new String[]{milestones.error(), projects.error(), ledger.error(), commandNotes.error()}) {
            if (err != null) log.warn("agent", "state.load_failed", err, null, null);
        }
        this.gate = new ScheduleGate(clock, host::onlinePlayers);
        this.persona = new PersonaSettings(cfg, new Personas(prompts.configDir()), new PersonaState(stateDir.resolve("persona.json")), prefs, log);
        this.balance = new BalanceMonitor(cfg, rules, http, log, () -> {
            List<String> l = new ArrayList<>();
            l.add(activeProvider());
            l.addAll(cfg.get().fallbackProviders);
            return l;
        }, msgs -> sendAlerts(msgs, this.rules.get()));
        this.chat = new ChatIntake(this, new ChatIntake.Owner() {
            @Override
            public boolean accepting() {
                return !locked() && enabled();
            }

            @Override
            public String autoBlock(Trigger.Kind k) {
                return AgentService.this.autoBlock(k, AgentService.this.rules.get());
            }

            @Override
            public boolean submit(Trigger t) {
                return AgentService.this.submit(t);
            }

            @Override
            public Scheduler timer() {
                return AgentService.this.timer();
            }
        }, cfg, rules, host, prefs, log, clock);
        this.router = new CloudRouter(this, new CloudRouter.Owner() {
            @Override
            public boolean enabled() {
                return AgentService.this.enabled();
            }

            @Override
            public boolean submitLocal(Trigger t) {
                return AgentService.this.submitLocal(t);
            }

            @Override
            public String activeProvider() {
                return AgentService.this.activeProvider();
            }

            @Override
            public Scheduler timer() {
                return AgentService.this.timer();
            }
        }, cfg, rules, host, log, memory, prompts, persona, prefs, projects, clock);
        this.runner = new RunExecutor(this, new RunExecutor.Owner() {
            @Override
            public boolean locked() {
                return AgentService.this.locked();
            }

            @Override
            public boolean enabled() {
                return AgentService.this.enabled();
            }

            @Override
            public String activeProvider() {
                return AgentService.this.activeProvider();
            }

            @Override
            public void alert(List<String> alerts, Rules rl) {
                sendAlerts(alerts, rl);
            }
        }, cfg, rules, host, tools, http, log, memory, quota, prompts, persona, gate, balance, prefs, projects, clock, onRunFinished);
        this.cloudTools = CloudTools.create(router, this, tools);
    }

    /** 玩家互动偏好与状态（风格、不想提的话题、"别说了"、连续受挫）。 */
    public PlayerPrefs prefs() {
        return prefs;
    }

    /** 战斗记录：死前 30 秒、附近队友、是否捡回、救下队友。 */
    public CombatLog combat() {
        return combat;
    }

    /** 每名玩家 / 全服的"第一次"。 */
    public Milestones milestones() {
        return milestones;
    }

    /** 玩家的工程和打算。 */
    public ProjectStore projects() {
        return projects;
    }

    /** 建筑账本（谁在哪一带放了多少方块）。 */
    public BuildLedger ledger() {
        return ledger;
    }

    /** 指令资料：原版资料 + AI 用过模组指令后记下的笔记。 */
    public CommandNotes commandNotes() {
        return commandNotes;
    }

    /** 落盘（关服时由平台层调用；定时器也会定期调用）。 */
    public void saveState() {
        projects.saveIfDirty();
        ledger.saveIfDirty();
        milestones.saveIfDirty();
    }

    // ———————————————————— 生命周期 ————————————————————

    public synchronized void start() {
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(50), r -> {
            Thread t = new Thread(r, "aiadmin-agent");
            t.setDaemon(true);
            return t;
        });
        timer = timers.get();
        gate.started();
        lastPatrol = clock.millis(); // 开服后先等一个完整间隔再巡检
        timer.repeat(this::tick, 30_000L, 30_000L);
        balance.start();
        timer.schedule(balance::refreshAsync, 20_000L); // 开服后查一次余额
        log.info("agent", "service.start", "AI 管理机器人已启动", null, JsonlLog.data("provider", activeProvider()));
    }

    public void stop() {
        RunContext c;
        synchronized (this) {
            c = runner.current();
            if (timer != null) timer.shutdownNow();
            balance.stop();
            if (worker != null) worker.shutdownNow(); // 中断正在进行的 HTTP 请求
        }
        if (c != null) c.cancel();
        router.wakeAll(); // 关服时让挂着的 wait_events 立即返回
        saveState();
        try {
            if (worker != null) worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("agent", "service.stop", "AI 管理机器人已停止", null, null);
    }

    private synchronized Scheduler timer() {
        return timer;
    }

    // ———————————————————— 触发入口 ————————————————————

    /** 管理员提问。返回立即回给管理员的提示（排队/忙/停用）。 */
    public synchronized String ask(String requester, String prompt, String provider, Consumer<String> reply) {
        if (lockReason != null) return "拒绝：设置文件有误，机器人已锁定（" + lockReason + "）。修好后 /aibot reload";
        if (provider != null && !cfg.get().providers.containsKey(provider)) return "没有这个接入点：" + provider + "（可用：" + providerNames() + "）";
        String blocked = quota.blocked(rules.get().quota, true, LocalDateTime.now(clock));
        if (blocked == null) blocked = gate.block(rules.get(), true);
        if (blocked != null) return "拒绝：" + blocked;
        if (!submit(Trigger.admin(requester, prompt, provider, reply))) return "机器人正忙（排队已满），请稍后再试";
        int ahead = worker.getQueue().size() - 1 + (runner.current() != null ? 1 : 0);
        return ahead > 0 ? "已收到，前面还有 " + ahead + " 个任务" : "已收到，正在思考…";
    }

    public synchronized String patrolNow() {
        lastPatrol = clock.millis();
        return submit(Trigger.patrol(true)) ? "已安排一次巡检" : "排队已满";
    }

    /**
     * 服务器事件。subject 用于冷却分组（一般是玩家名，告警类传 null）。
     * 在服务端主线程调用，只做内存操作，不阻塞。
     */
    public synchronized void onEvent(String kind, String subject, String detail) {
        BotConfig c = cfg.get();
        long now = clock.millis();
        prefs.frustration(new PlayerPrefs.Frustration(c.social.frustratedDeaths, c.social.frustratedWindowMinutes, c.social.frustratedCalmMinutes));
        if (kind.equals(EV_DEATH) && subject != null) prefs.recordDeath(subject, now); // 受挫判定不管事件开没开都要计
        if (!enabled() || !eventEnabled(c.events, kind)) return;
        // 玩家说过"别说了"：他的进出、死亡、成就等不交给 AI（告警类没有 subject，不受影响）；subject 可能是"玩家|类别"
        if (subject != null && prefs.quiet(subject.contains("|") ? subject.substring(0, subject.indexOf('|')) : subject)) {
            log.info("agent", "trigger.dropped", "不交给 AI：" + subject + " 正在安静中（" + kind + "）", null, null);
            return;
        }
        boolean alert = kind.equals(EV_TPS) || kind.equals(EV_ERROR);
        long cd = (alert ? c.events.alertCooldownSeconds : c.events.cooldownSeconds) * 1000L;
        String key = kind + ":" + (subject == null ? "" : subject.toLowerCase(Locale.ROOT));
        Long last = cooldown.get(key);
        if (last != null && now - last < cd) return;
        cooldown.put(key, now);
        // 清理只删一天前的：告警冷却（events.alert_cooldown_seconds）可以超过 1 小时，按 1 小时清会让告警提前再次触发
        if (cooldown.size() > 2000) cooldown.entrySet().removeIf(e -> now - e.getValue() > ChatIntake.DAY_MS);
        pendingEvents.add("[" + Text.now() + "] " + kind + "：" + detail);
        if (!eventFlushPending && timer != null && !timer.isShutdown()) {
            eventFlushPending = true;
            timer.schedule(this::flushEvents, c.events.debounceSeconds * 1000L);
        }
    }

    private synchronized void flushEvents() {
        eventFlushPending = false;
        if (pendingEvents.isEmpty()) return;
        List<String> batch = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        String why = autoBlock(Trigger.Kind.EVENT, rules.get());
        if (why != null) {
            log.info("agent", "trigger.dropped", "丢弃事件：" + why, null, JsonlLog.data("events", batch));
            return;
        }
        submit(Trigger.events(batch));
    }

    /** 平台层把服务器动态交给调度层，用来给聊天回复带上下文。 */
    public void setActivity(Activity a) {
        chat.setActivity(a);
    }

    /** 玩家公屏聊天（服务端主线程调用，只做内存操作）。返回这条消息是否会唤醒机器人。规则见 {@link ChatIntake#onChat}。 */
    public boolean onChat(String player, String text, boolean isOp) {
        return chat.onChat(player, text, isOp);
    }

    /** /ai 私聊。返回 null = 收下了，否则是要告诉玩家的原因。规则见 {@link ChatIntake#onPrivateChat}。 */
    public String onPrivateChat(String player, String text, boolean isOp) {
        return chat.onPrivateChat(player, text, isOp);
    }

    /** 每 30 秒：落盘、清理冷却表、余额到点、云端收回 / 超时关闭、巡检到点。 */
    private void tick() {
        try {
            BotConfig c = cfg.get();
            Rules rl = rules.get();
            long now = clock.millis();
            // 工程进度每 30 秒落盘一次；建筑账本较大，5 分钟一次（关服时 saveState 再写一次）
            projects.saveIfDirty();
            milestones.saveIfDirty();
            chat.prune(now);
            if (now - lastLedgerSave >= 300_000L) {
                lastLedgerSave = now;
                ledger.saveIfDirty();
            }
            if (balance.due(rl, now)) balance.refreshAsync();
            router.tick(c);
            synchronized (this) {
                if (!enabled() || !c.patrol.enabled) return;
                if (now - lastPatrol < c.patrol.intervalMinutes * 60_000L) return;
            }
            // onlinePlayers 不需要持锁（读的是服务端玩家表大小）
            if (c.patrol.onlyWhenPlayersOnline && host.onlinePlayers() == 0) return;
            synchronized (this) {
                lastPatrol = now;
                String blocked = autoBlock(Trigger.Kind.PATROL, rl);
                if (blocked != null) {
                    log.info("agent", "trigger.dropped", "跳过巡检：" + blocked, null, null);
                    return;
                }
                submit(Trigger.patrol(false));
            }
        } catch (RuntimeException e) {
            log.unhandled("agent", "timer.error", "定时器出错", null, e); // 定时器线程不能因异常停掉；未处理异常每次都写
        }
    }

    // ———————————————————— 提交 ————————————————————

    /** 自动触发能不能跑：交给云端时不花本地 API 的钱、不受本地额度限制；启停限制（时段、星期、人数）两边都遵守。返回拒绝原因或 null。 */
    private String autoBlock(Trigger.Kind k, Rules rl) {
        String why = router.wants(k) ? null : quota.blocked(rl.quota, false, LocalDateTime.now(clock));
        return why != null ? why : gate.block(rl, false);
    }

    /**
     * 云端优先，本地兜底：聊天/事件/巡检在云端在线时进收件箱，并安排 handoff 秒后的兜底检查——那时还没被取走就交给本地 API。
     * 收件箱满了、云端不在线、管理员提问，都直接走本地。在本对象的锁内调用。
     */
    private boolean submit(Trigger t) {
        if (router.wants(t.kind()) && router.offer(t, persona.chattiness())) return true;
        return submitLocal(t);
    }

    /** 交给本地代理线程。在本对象的锁内调用。 */
    private boolean submitLocal(Trigger t) {
        if (worker == null || worker.isShutdown()) return false;
        if (worker.getQueue().size() >= cfg.get().limits.queueMax) {
            log.warn("agent", "trigger.dropped", "排队已满，丢弃：" + t.label(), null, null);
            return false;
        }
        try {
            worker.execute(() -> runner.runSafely(t));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void sendAlerts(List<String> alerts, Rules rl) {
        for (String a : alerts) {
            log.warn("agent", "quota.alert", a, null, null);
            try {
                host.alertAdmins(a, rl.quota.alertInGame);
            } catch (RuntimeException e) {
                log.error("agent", "quota.alert_failed", "发送额度提醒失败", null, e);
            }
        }
    }

    // ———————————————————— 云端值守（见 CloudRouter） ————————————————————

    /** 远程接口启动/停止时设置推送通道（null = 没有）。 */
    public void cloudPush(CloudPush p) {
        router.push(p);
    }

    /** 推送被云端确认收到（McpEvents 回调）：这条消息的兜底计时放宽到 webhook_handoff_seconds。 */
    public void cloudDelivered(String itemId) {
        router.delivered(itemId);
    }

    /** 云端带着 item_id 做查询：刷新这条消息的活动时间（见 CloudInbox.touch）。 */
    public boolean cloudTouch(String itemId) {
        return router.touch(itemId);
    }

    /** 远程的云端值守工具每次调用都算云端心跳（RemoteTools 调用）。 */
    public void cloudHeartbeat() {
        router.heartbeat();
    }

    /** 为某条收件箱消息执行动作时用的上下文；null = 这条已不是 TAKEN，不能再为它执行动作。见 {@link CloudRouter#context}。 */
    public RunContext cloudContext(String itemId) {
        return router.context(itemId);
    }

    public Tools cloudTools() {
        return cloudTools;
    }

    /** 云端 server_power 用的宿主机启停程序；测试里换成假的。 */
    public void setServerPower(ServerPower.Runner r) {
        router.serverPower(r);
    }

    /** 平台层的"重读设置"（/aibot reload 同一个方法），云端 set_setting 写完 config.json 后调用；返回 null = 成功。 */
    public void setReloader(Supplier<String> r) {
        router.reloader(r);
    }

    // ———————————————————— 聊天模式与预设（见 ChatIntake、PersonaSettings） ————————————————————

    public String chatMode() {
        return chat.mode();
    }

    /** 运行时切换聊天模式（reload 或重启后恢复设置文件的值）。 */
    public String setChatMode(String mode) {
        return chat.setMode(mode);
    }

    /** 当前预设模式：/aibot mode 切换过就用切换后的（文件已被删掉时退回设置文件的值）。 */
    public String personaMode() {
        return persona.mode();
    }

    public String prankLevel() {
        return persona.prankLevel();
    }

    public List<String> personaNames() {
        return persona.names();
    }

    public String personaInfo() {
        return persona.info();
    }

    /** 切换预设模式（记住，重启后仍有效）；reset = 回到设置文件的值。 */
    public String setPersonaMode(String mode) {
        return persona.setMode(mode);
    }

    /** 切换恶作剧档位（记住）；reset = 回到设置文件的值。 */
    public String setPrankLevel(String level) {
        return persona.setPrankLevel(level);
    }

    /** 话量：/aibot talk 切换过就用切换后的，否则用 config.json 的 social.chattiness。 */
    public String chattiness() {
        return persona.chattiness();
    }

    public static String talkLabel(String t) {
        return PersonaSettings.talkLabel(t);
    }

    /** 切换话量（记住，重启后仍有效）；reset = 回到设置文件的值。 */
    public String setChattiness(String level) {
        return persona.setChattiness(level);
    }

    // ———————————————————— 额度与余额（见 BalanceMonitor） ————————————————————

    /** 查询余额并处理阈值（调用线程上做网络请求）。all = 所有配置了余额接口的接入点。 */
    public List<Balance.Info> refreshBalances(boolean all) {
        return balance.refresh(all);
    }

    /** /aibot quota：刷新余额后回复完整额度报告（在余额线程里做网络请求，不阻塞服务端主线程）。 */
    public void quotaReport(Consumer<String> reply) {
        balance.report(quota, reply);
    }

    /** 同步版的额度报告（远程管理接口用）：最多等 timeoutMs。 */
    public String quotaReportBlocking(long timeoutMs) {
        CompletableFuture<String> f = new CompletableFuture<>();
        quotaReport(f::complete);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "查询额度超时或失败：" + e.getClass().getSimpleName();
        }
    }

    // ———————————————————— 管理 ————————————————————

    /** 设置文件读取失败时锁定（reason 非 null），reload 成功后解锁（null）。 */
    public synchronized void setLockReason(String reason) {
        lockReason = reason;
    }

    public synchronized boolean locked() {
        return lockReason != null;
    }

    public synchronized boolean cancel() {
        RunContext c = runner.current();
        if (c == null) return false;
        c.cancel();
        return true;
    }

    public synchronized void setEnabled(boolean on) {
        enabledOverride = on;
    }

    public synchronized boolean enabled() {
        return enabledOverride && cfg.get().enabled;
    }

    /** 运行时切换接入点（重启或 /aibot reload 后恢复设置文件里的 active_provider）。 */
    public synchronized String setProvider(String name) {
        if (!cfg.get().providers.containsKey(name)) return "没有这个接入点：" + name + "（可用：" + providerNames() + "）";
        providerOverride = name;
        return "已切换到 " + name + "（" + cfg.get().providers.get(name).model + "），重启或 reload 后恢复设置文件的值";
    }

    public synchronized void resetOverrides() {
        providerOverride = null;
        chat.resetMode();
    }

    public synchronized String activeProvider() {
        return providerOverride != null && cfg.get().providers.containsKey(providerOverride) ? providerOverride : cfg.get().activeProvider;
    }

    public synchronized String providerNames() {
        return String.join(" / ", cfg.get().providers.keySet());
    }

    /** 解除"连续失败暂停"。 */
    public boolean resume() {
        return gate.resume();
    }

    public synchronized String status() {
        BotConfig c = cfg.get();
        String p = activeProvider();
        ProviderConfig pc = c.providers.get(p);
        Memory.Entry last = memory.last();
        RunContext cur = runner.current();
        StringBuilder sb = new StringBuilder();
        if (lockReason != null) sb.append("已锁定：设置文件有误（").append(lockReason).append("）\n");
        sb.append("状态：").append(enabled() ? "运行中" : "已停用").append("；接入点：").append(p).append("（").append(pc.type).append(" / ").append(pc.model)
                .append(pc.resolveKey().isEmpty() ? "，未配置密钥！" : "").append(balance.stopped(p) ? "，余额不足已停用" : "").append("）\n");
        sb.append("当前任务：").append(cur == null ? "无" : cur.trigger.label()).append("；排队：").append(worker == null ? 0 : worker.getQueue().size())
                .append("；本次开服累计运行 ").append(runner.runsTotal()).append('\n');
        sb.append("巡检：").append(c.patrol.enabled ? "每 " + c.patrol.intervalMinutes + " 分钟" + (c.patrol.onlyWhenPlayersOnline ? "（有人在线时）" : "") : "关闭");
        String cm = chat.mode();
        sb.append("；聊天：").append(switch (cm) {
            case "off" -> "不回应";
            case "all" -> "所有聊天都看（自己判断回不回）";
            case "custom" -> "关键词或自定义条件（" + c.chat.conditions.size() + " 条）触发";
            default -> "提到关键词才回（" + String.join("、", ChatIntake.keywords(c)) + "）";
        });
        PersonaSettings.Snapshot ps = persona.snapshot();
        sb.append("；模式：").append(Personas.label(ps.mode())).append("；话量：").append(talkLabel(persona.chattiness()));
        if (Personas.PRANK.equals(ps.mode())) sb.append("（").append(ps.level().label).append("）");
        if (c.remote.cloud.enabled) sb.append('\n').append(router.statusLine());
        String blocked = quota.blocked(rules.get().quota, false, LocalDateTime.now(clock));
        if (blocked != null) sb.append("\n额度：").append(blocked);
        String sched = gate.block(rules.get(), false);
        if (sched != null) sb.append("\n启停：自动运行暂停（").append(sched).append("）");
        if (last != null) {
            sb.append("\n上次：[").append(last.time()).append("] ").append(last.trigger()).append(" — ")
                    .append(last.error() != null && last.summary().isEmpty() ? "失败：" + Text.brief(last.error(), 100) : Text.brief(last.summary(), 120));
        }
        return sb.toString();
    }

    static boolean eventEnabled(BotConfig.Events e, String kind) {
        return switch (kind) {
            case EV_JOIN -> e.playerJoin;
            case EV_LEAVE -> e.playerLeave;
            case EV_DEATH -> e.playerDeath;
            case EV_ADVANCEMENT -> e.advancement;
            case EV_TPS -> e.tpsLow;
            case EV_ERROR -> e.serverError;
            case EV_CHAT -> !e.chatKeywords.isEmpty();
            case EV_MILESTONE -> e.milestones;
            case EV_DEATH_FOLLOWUP -> e.playerDeath && e.deathFollowup;
            default -> false;
        };
    }
}
