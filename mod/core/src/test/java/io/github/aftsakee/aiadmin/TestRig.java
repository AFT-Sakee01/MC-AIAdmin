package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Scheduler;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 测试共用的搭建：假的服务器动作和宿主、手动推进的时钟和定时器、按常用默认值搭好的 AgentService。
 * 以前各测试类各写一遍 ServerActions / Host 假实现（审查报告里约 250 行重复）。
 * <p>用法：{@code rig = new TestRig(tmp)} → 按需改 rig.cfg / rig.rules / rig.tools → {@code rig.start()}；@AfterEach 里 {@code rig.close()}。
 * 默认值：deepseek 接入点有密钥、长期记忆用 digest（不调模型）、开服延迟 0。
 */
final class TestRig implements AutoCloseable {
    final Path dir;
    final BotConfig cfg = new BotConfig();
    final Rules rules = new Rules();
    final FakeHttp http = new FakeHttp();
    final FakeActions actions = new FakeActions();
    final FakeHost host = new FakeHost();
    final Activity activity = new Activity();
    final Tools tools = new Tools();
    final JsonlLog log;
    /** 注册动作工具时是否接上玩家偏好（发言前检查"别说了"、不想提的话题，恶作剧免疫）。默认不接，和多数测试原来的搭法一样。 */
    boolean actionPrefs;
    /** start() 时是否生成提示词文件（Prompts.ensure）。远程接口测试原来不生成。 */
    boolean ensurePrompts = true;
    AgentService svc;
    private boolean registered;

    TestRig(Path dir) {
        this(dir, true);
    }

    /** defaults=false：BotConfig / Rules 保持出厂值（远程接口测试用：不需要接入点密钥，启停限制也照出厂设置）。 */
    TestRig(Path dir, boolean defaults) {
        this.dir = dir;
        if (defaults) {
            cfg.providers.get("deepseek").apiKey = "k";
            cfg.compression.memory.type = "digest";
            rules.schedule.startupDelaySeconds = 0;
        }
        log = new JsonlLog(dir.resolve("logs"), null);
    }

    /** 注册动作工具（还没注册过的话）、生成提示词文件、创建并启动服务（真实时钟、真实定时线程）。 */
    AgentService start() throws IOException {
        return start(null, null);
    }

    /** 同上，但用给定的时钟和定时器（例如 {@link TestClock} + {@link ManualScheduler}）；传 null 用真实的。 */
    AgentService start(Clock clock, Scheduler timer) throws IOException {
        registerActions();
        Prompts prompts = new Prompts(dir.resolve("config"));
        if (ensurePrompts) prompts.ensure(cfg);
        svc = clock == null && timer == null
                ? new AgentService(() -> cfg, () -> rules, host, tools, http, log, dir.resolve("state"), prompts, null)
                : new AgentService(() -> cfg, () -> rules, host, tools, http, log, dir.resolve("state"), prompts, null,
                clock != null ? clock : Clock.systemDefaultZone(), timer != null ? () -> timer : () -> Scheduler.threaded("test-timer"));
        svc.start();
        return svc;
    }

    /**
     * 把 run_command / send_chat 等动作工具注册到 rig.tools（只注册一次）。需要控制工具顺序的测试（先加别的工具、
     * 之后再加 SocialTools / OwnerTools）可以在 start() 之前自己调用。
     */
    void registerActions() {
        if (registered) return;
        registered = true;
        ActionTools.register(tools, actions, () -> cfg, () -> rules, log, activity, () -> actionPrefs && svc != null ? svc.prefs() : null);
    }

    @Override
    public void close() {
        if (svc != null) svc.stop();
    }

    /** OpenAI 格式的一条纯文字回复（10 + 5 token）。 */
    static String text(String t) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + t + "\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }

    /** 假的服务器动作：执行过的指令进 executed，发出的聊天进 chats（私聊写成"对象←内容"）。线程安全：代理线程写、测试线程读。 */
    static final class FakeActions implements ActionTools.ServerActions {
        final List<String> executed = Collections.synchronizedList(new ArrayList<>());
        final List<String> chats = Collections.synchronizedList(new ArrayList<>());
        /** 指令结果，默认成功、输出 "ok"。 */
        volatile Function<String, CommandOutcome> outcome = c -> new CommandOutcome(true, 1, "ok");
        /** 每次发聊天后额外做的事（message, target），例如写进动态。 */
        volatile BiConsumer<String, String> onChat = (m, t) -> {};

        @Override
        public CommandOutcome runCommand(String command, RunContext ctx) {
            executed.add(command);
            return outcome.apply(command);
        }

        @Override
        public String sendChat(String message, String target, RunContext ctx) {
            chats.add((target == null ? "" : target + "←") + message);
            onChat.accept(message, target);
            return null;
        }
    }

    /** 假的宿主：在线人数可调，提醒和给玩家的系统提示都记下来。 */
    static final class FakeHost implements AgentService.Host {
        volatile int online = 1;
        volatile String snapshot = "{}";
        final List<String> alerts = Collections.synchronizedList(new ArrayList<>());
        /** tellPlayer 的记录："玩家：内容"。 */
        final List<String> told = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int onlinePlayers() {
            return online;
        }

        @Override
        public String snapshotJson() {
            return snapshot;
        }

        @Override
        public String serverLabel() {
            return "测试服";
        }

        @Override
        public void alertAdmins(String message, boolean inGame) {
            alerts.add(message);
        }

        @Override
        public void tellPlayer(String player, String message) {
            told.add(player + "：" + message);
        }
    }

    /** 只在测试推进时才走的时钟：从创建时的真实时间开始（和 Activity 等仍用系统时钟的地方对得上），时区用系统默认。 */
    static final class TestClock extends Clock {
        private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        private final ZoneId zone;

        TestClock() {
            this(ZoneId.systemDefault());
        }

        private TestClock(ZoneId zone) {
            this.zone = zone;
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }

        void set(long millis) {
            now.set(millis);
        }

        @Override
        public long millis() {
            return now.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            TestClock c = new TestClock(z);
            c.set(millis());
            return c;
        }
    }

    /**
     * 手动推进的定时器：任务按到期时间排队，{@link #advance} 推进时钟并在调用线程上依次执行到期的任务
     * （任务执行时新排的、已经到期的任务也会在这次推进里执行）。不开线程，所以兜底 / 合并的时序完全确定。
     */
    static final class ManualScheduler implements Scheduler {
        private record Task(long due, long seq, Runnable run, long period) {}

        private final TestClock clock;
        private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> a.due != b.due ? Long.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
        private long seq;
        private boolean shut;

        ManualScheduler(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized void schedule(Runnable task, long delayMs) {
            if (!shut) queue.add(new Task(clock.millis() + Math.max(0, delayMs), seq++, task, 0));
        }

        @Override
        public synchronized void repeat(Runnable task, long initialDelayMs, long periodMs) {
            if (!shut) queue.add(new Task(clock.millis() + Math.max(0, initialDelayMs), seq++, task, Math.max(1, periodMs)));
        }

        @Override
        public synchronized boolean isShutdown() {
            return shut;
        }

        @Override
        public synchronized void shutdownNow() {
            shut = true;
            queue.clear();
        }

        /** 推进 ms 毫秒，执行这段时间内到期的任务；任务在调用线程上执行，锁外执行（任务里可以再排任务）。 */
        void advance(long ms) {
            long target = clock.millis() + ms;
            while (true) {
                Task t;
                synchronized (this) {
                    t = queue.peek();
                    if (shut || t == null || t.due > target) break;
                    queue.poll();
                    if (t.period > 0) queue.add(new Task(t.due + t.period, seq++, t.run, t.period));
                }
                if (t.due > clock.millis()) clock.set(t.due);
                t.run.run();
            }
            if (clock.millis() < target) clock.set(target);
        }

        /** 只执行已经到期的任务（例如 debounce 为 0 的合并）。 */
        void runDue() {
            advance(0);
        }
    }
}
