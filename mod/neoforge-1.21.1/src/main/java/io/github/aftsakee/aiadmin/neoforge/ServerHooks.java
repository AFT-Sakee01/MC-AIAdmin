package io.github.aftsakee.aiadmin.neoforge;

import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ConfigStore;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.remote.McpServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端生命周期与事件 → 调度层。只在专用服务器上启动。订阅、优先级和调用顺序见 {@link HookBus}（本类的处理者在每组里排第一个）。
 * <p>设置的生命周期：开服时读 config.json、rules.json 并补齐提示词文件；/aibot reload 重读。
 * 读取失败时：开服阶段 → 机器人保持停用（不会带着残缺规则运行）；reload 阶段 → 保留旧设置并报错。
 * <p>线程：事件处理在服务端主线程，基本只做内存操作（AgentService.onEvent/onChat 只是入队，由定时器和代理线程处理）；
 * 例外：开服/关服本身（读设置、建日志、落盘）、开发用的聊天文件轮询（-Daiadmin.devChatFile，每秒读一次文件）。
 */
final class ServerHooks {
    private static MinecraftServer server;
    private static JsonlLog log;
    private static volatile BotConfig cfg;
    private static volatile Rules rules;
    private static AgentService service;
    private static McBridge bridge;
    private static Prompts prompts;
    private static LogTap tap;
    private static McpServer remote;
    private static Path configDir;
    private static final Activity ACTIVITY = new Activity();

    static Activity activity() {
        return ACTIVITY;
    }
    private static final AtomicInteger RUNS_FINISHED = new AtomicInteger();
    private static String startupError;
    private static long lowSince;

    private ServerHooks() {}

    static BotConfig config() {
        return cfg;
    }

    /** 服务端已进入关服流程（ServerStoppingEvent 之后）。 */
    static boolean stopping() {
        McBridge b = bridge;
        return b != null && b.closed();
    }

    static Rules rules() {
        return rules;
    }

    static AgentService service() {
        return service;
    }

    static boolean active() {
        return service != null;
    }

    // ———————————————————— 生命周期 ————————————————————

    static void onStarting(ServerStartingEvent e) {
        if (!e.getServer().isDedicatedServer()) return;
        server = e.getServer();
        Path gameDir = FMLPaths.GAMEDIR.get();
        configDir = gameDir.resolve("config").resolve("aiadmin");
        log = new JsonlLog(gameDir.resolve("logs").resolve("aiadmin"), (level, msg) -> {
            if (level.equals("warn")) AiAdminMod.LOG.warn(msg);
            else AiAdminMod.LOG.error(msg);
        });
        prompts = new Prompts(configDir);
        startupError = reload(true);
        if (startupError != null) AiAdminMod.LOG.error("[AI管理] {}，机器人已锁定（包括管理员提问），修好后用 /aibot reload", startupError);

        tap = new LogTap();
        try {
            tap.install();
        } catch (RuntimeException ex) {
            log.error("hooks", "logtap.install_failed", "无法挂接服务端日志（get_server_log 不可用）", null, ex);
        }
        bridge = new McBridge(server);
        Tools tools = new Tools();
        McTools.register(tools, server, bridge, ACTIVITY, tap, gameDir, () -> rules);
        ActionTools.register(tools, bridge, () -> cfg, () -> rules, log, ACTIVITY, () -> service == null ? null : service.prefs(),
                () -> service == null ? null : service.commandNotes());
        io.github.aftsakee.aiadmin.agent.SocialTools.register(tools, () -> service == null ? null : service.prefs(), () -> cfg,
                () -> service == null ? null : service.commandNotes()); // 玩家互动偏好、指令笔记
        SocialHooks.registerTools(tools, server, bridge, () -> prompts.guide(cfg)); // get_commands
        StoryHooks.registerTools(tools, server, bridge); // get_death_story、list_projects、set_project、who_built
        TrackerTools.register(tools, server, bridge); // get_players_overview / get_player_activity（数据来自 TrackerHooks）
        GameDataTools.register(tools, server, bridge); // 物品/配方/标签/掉落/战利品查询、合成链路、玩家目标
        io.github.aftsakee.aiadmin.agent.OwnerTools.register(tools, () -> configDir, log); // 服主写的宏（macros.json）和知识文档（guides/）
        service = new AgentService(() -> cfg, () -> rules, bridge, tools, HttpTransport.jdk(), log, gameDir.resolve("aiadmin"), prompts,
                ServerHooks::onRunFinished);
        service.setLockReason(startupError);
        service.setActivity(ACTIVITY);
        service.setReloader(() -> reload(false)); // 云端 set_setting 写完 config.json 后重读
        service.start();
        TpaCommands.start(gameDir.resolve("aiadmin"));
        String ver = net.neoforged.fml.ModList.get().getModContainerById("aiadmin")
                .map(m -> m.getModInfo().getVersion().toString()).orElse("?");
        remote = new McpServer(() -> cfg, () -> rules, tools, service, log, ver);
        if (startupError == null) remote.start(configDir);
        AiAdminMod.LOG.info("[AI管理] 已启动：接入点 {}，设置目录 {}", service.activeProvider(), configDir);
    }

    static McpServer remote() {
        return remote;
    }

    /**
     * 读（或重读）config.json、rules.json，补齐提示词文件。成功返回 null，失败返回原因。
     * 首次失败时 cfg 设成停用状态的默认值，保证其他代码拿到的永远是非 null 的设置。
     */
    static synchronized String reload(boolean first) {
        try {
            var c = ConfigStore.loadConfig(configDir.resolve("config.json"));
            var r = ConfigStore.loadRules(configDir.resolve("rules.json"), c.value());
            List<String> warn = new ArrayList<>(c.warnings());
            warn.addAll(r.warnings());
            List<Path> created = prompts.ensure(c.value());
            cfg = c.value();
            rules = r.value();
            if (service != null) {
                service.resetOverrides();
                service.setLockReason(null);
            }
            if (remote != null) remote.start(configDir); // 按新设置重启远程接口（关掉、换端口、换令牌都在这里生效）
            // tpa 的开关/权限写在指令树的 requires 里，玩家手里的指令树要重新下发才会更新。
            // 只投递到主线程、不等待结果：reload 持有本类的锁，等主线程会有死锁风险。
            MinecraftServer s = server;
            if (s != null) s.execute(() -> s.getPlayerList().getPlayers().forEach(p -> s.getCommands().sendCommands(p)));
            log.info("hooks", "config.loaded", "设置已加载", null, JsonlLog.data("config_created", c.created(), "rules_created", r.created(),
                    "prompt_files_created", created.stream().map(Path::toString).toList(), "warnings", warn));
            for (String w : warn) AiAdminMod.LOG.warn("[AI管理] {}", w);
            if (c.created() || r.created()) {
                AiAdminMod.LOG.warn("[AI管理] 已生成默认设置 {}，请填写 API key 后 /aibot reload", configDir);
            }
            return null;
        } catch (ConfigStore.ConfigException | java.io.IOException | RuntimeException ex) {
            log.error("hooks", "config.invalid", "设置文件有误：" + ex.getMessage(), null, JsonlLog.data());
            if (first || cfg == null) {
                // 首次失败：给一个停用的默认值保证非 null，同时由 setLockReason 锁定（不能用默认规则替服主做决定）
                BotConfig off = new BotConfig();
                off.enabled = false;
                cfg = off;
                rules = new Rules();
            }
            return String.valueOf(ex.getMessage());
        }
    }

    static void onStarted(ServerStartedEvent e) {
        // 仅开发/端到端测试：-Daiadmin.devHaltAfterSeconds=N，开服 N 秒后自动关服（用于不会产生运行的测试场景）
        int haltAfter = Integer.getInteger("aiadmin.devHaltAfterSeconds", 0);
        if (haltAfter > 0) {
            MinecraftServer s = e.getServer();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(haltAfter * 1000L);
                } catch (InterruptedException ignored) {
                    return;
                }
                s.execute(() -> s.halt(false));
            }, "aiadmin-dev-halt");
            t.setDaemon(true);
            t.start();
        }
        if (service == null) return;
        // 仅开发/端到端测试：-Daiadmin.devConsoleCommands="aibot ask …|aibot status"，以控制台身份执行
        String dev = System.getProperty("aiadmin.devConsoleCommands", "");
        // Windows 上经 Gradle 命令行传入的中文会变成问号，所以也支持从 UTF-8 文件读取（-Daiadmin.devConsoleCommandsFile=路径）
        String devFile = System.getProperty("aiadmin.devConsoleCommandsFile", "");
        if (!devFile.isBlank()) {
            try {
                dev = java.nio.file.Files.readString(Path.of(devFile), java.nio.charset.StandardCharsets.UTF_8).replace("\r", "").replace('\n', '|');
            } catch (java.io.IOException ex) {
                AiAdminMod.LOG.error("[AI管理] 读不了开发指令文件 {}", devFile);
            }
        }
        if (!dev.isBlank()) {
            CommandSourceStack console = server.createCommandSourceStack();
            for (String cmd : dev.split("\\|")) {
                if (cmd.isBlank()) continue;
                // 仅开发：以 "chat 玩家名 内容" 模拟玩家聊天（开发服务端没有真实玩家），走和真实聊天相同的路径
                if (cmd.trim().startsWith("chat ")) {
                    String[] p = cmd.trim().split(" ", 3);
                    if (p.length == 3) {
                        boolean op = p[1].startsWith("op:"); // "chat op:名字 内容" 模拟 OP 说话
                        handleChat(op ? p[1].substring(3) : p[1], p[2], op);
                    }
                    continue;
                }
                server.getCommands().performPrefixedCommand(console, cmd.trim());
            }
        }
    }

    private static void onRunFinished() {
        int n = RUNS_FINISHED.incrementAndGet();
        int stopAfter = Integer.getInteger("aiadmin.devStopAfterRuns", 0);
        if (stopAfter > 0 && n >= stopAfter && server != null) {
            AiAdminMod.LOG.info("[AI管理] 开发模式：已完成 {} 次运行，关服", n);
            server.execute(() -> server.halt(false));
        }
    }

    static void onStopping(ServerStoppingEvent e) {
        if (bridge != null) bridge.close(); // 先关闸门：之后代理/远程线程不再往主线程投递（见 McBridge.close）
        if (service == null) return;
        if (remote != null) remote.stop();
        TpaCommands.stop();
        remote = null;
        service.stop();
        if (tap != null) tap.uninstall();
        log.close();
        service = null;
        server = null;
    }

    static void onRegisterCommands(RegisterCommandsEvent e) {
        AdminCommand.register(e.getDispatcher());
        TpaCommands.register(e.getDispatcher());
    }

    // ———————————————————— 事件 ————————————————————

    static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (service == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        String n = McFmt.name(p);
        ACTIVITY.add("join", n, "进入服务器");
        service.onEvent(AgentService.EV_JOIN, n, n + " 进入了服务器（现在在线 " + server.getPlayerCount() + " 人）");
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (service == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        TpaCommands.onLogout(p.getUUID()); // 他发出和收到的传送请求都作废（静默）
        String n = McFmt.name(p);
        ACTIVITY.add("leave", n, "离开服务器");
        service.onEvent(AgentService.EV_LEAVE, n, n + " 离开了服务器");
    }

    /** 订阅时用 LOWEST、不收已取消的（见 HookBus）：复活类模组（不死图腾之外的）会在更低优先级取消死亡，默认优先级会先把死亡交给 AI。 */
    static void onDeath(LivingDeathEvent e) {
        if (service == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        String n = McFmt.name(p);
        String msg = e.getSource().getLocalizedDeathMessage(p).getString();
        ACTIVITY.add("death", n, msg);
        String story = StoryHooks.playerDeath(p, msg); // 死前 30 秒、附近队友、掉落（之后还会跟进是否捡回）
        // 完整维度 id（minecraft:overworld），和之后 CombatLog 发的跟进事件里的写法一致
        service.onEvent(AgentService.EV_DEATH, n, msg + "（" + McFmt.dimId(p) + " " + McFmt.xyz(p.blockPosition()) + "）"
                + (story.isEmpty() ? "" : "。" + story));
    }

    static void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        if (service == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        var display = e.getAdvancement().value().display();
        if (display.isEmpty() || !display.get().shouldAnnounceChat()) return; // 配方解锁之类的不算
        String n = McFmt.name(p);
        String title = display.get().getTitle().getString();
        ACTIVITY.add("advancement", n, title);
        service.onEvent(AgentService.EV_ADVANCEMENT, n, n + " 达成进度「" + title + "」");
    }

    static void onChat(ServerChatEvent e) {
        if (service == null) return;
        handleChat(McFmt.name(e.getPlayer()), e.getRawText());
    }

    /** 记进动态，再交给调度层判断要不要回（off / mention / custom / all 见 config.json 的 chat）。OP = 权限等级 ≥ 2。 */
    private static long devChatRead;

    /**
     * 仅开发/端到端测试（-Daiadmin.devChatFile=路径）：每秒读一次这个 UTF-8 文件新追加的行，"chat 玩家 内容" / "chat op:玩家 内容"
     * 按真实聊天处理。开服时执行的 devConsoleCommands 做不到"服务器运行中途再说话"（云端值守的 e2e 需要）。
     */
    private static void devChatPoll() {
        String f = System.getProperty("aiadmin.devChatFile", "");
        if (f.isBlank()) return;
        try {
            byte[] all = java.nio.file.Files.readAllBytes(Path.of(f));
            if (all.length <= devChatRead) return;
            String added = new String(all, (int) devChatRead, all.length - (int) devChatRead, java.nio.charset.StandardCharsets.UTF_8);
            int end = added.lastIndexOf('\n');
            if (end < 0) return; // 等整行写完
            devChatRead += added.substring(0, end + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            for (String line : added.substring(0, end).split("\n")) {
                String[] p = line.replace("\r", "").trim().split(" ", 3);
                if (p.length == 3 && p[0].equals("chat")) {
                    boolean op = p[1].startsWith("op:");
                    handleChat(op ? p[1].substring(3) : p[1], p[2], op);
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // 文件还没建好等情况：下一秒再试
        }
    }

    static void handleChat(String player, String text) {
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayerByName(player);
        handleChat(player, text, Cmds.isOp(server, p));
    }

    static void handleChat(String player, String text, boolean isOp) {
        ACTIVITY.add("chat", player, text);
        if (service != null) service.onChat(player, text, isOp);
    }

    /** 玩家之间私聊的指令（原版和常见别名）：动态里只记"谁私聊了谁"，不记内容。 */
    static final java.util.Set<String> WHISPER = io.github.aftsakee.aiadmin.util.CommandPrivacy.WHISPER;
    static final java.util.Set<String> TEAM_MSG = io.github.aftsakee.aiadmin.util.CommandPrivacy.TEAM_MSG;

    static void onCommand(CommandEvent e) {
        if (service == null) return;
        CommandSourceStack src = e.getParseResults().getContext().getSource();
        ServerPlayer p = src.getPlayer();
        if (p == null) return; // 控制台、命令方块、机器人自己的指令不记
        String raw = e.getParseResults().getReader().getString();
        String name = McFmt.name(p);
        // 私聊正文、登录密码不进动态（AI 能看到），execute … run 套一层也一样；规则见 CommandPrivacy（有单测）
        io.github.aftsakee.aiadmin.util.CommandPrivacy.Entry en = io.github.aftsakee.aiadmin.util.CommandPrivacy.describe(raw);
        if (en != null) ACTIVITY.add(en.kind(), name, en.text());
    }

    /** 每 20 tick（每秒，HookBus 调用）：TPS 监测、把日志旁路收集到的错误交给调度层。传送请求过期由 HookBus 每 tick 调 TpaCommands.tick。 */
    static void onTick(MinecraftServer s, long tick) {
        if (service == null) return;
        devChatPoll();
        BotConfig c = cfg;
        double mspt = s.getAverageTickTimeNanos() / 1_000_000.0;
        long now = System.currentTimeMillis();
        if (mspt > c.events.tpsLowMspt) {
            if (lowSince == 0) lowSince = now;
            else if (now - lowSince >= c.events.tpsLowSeconds * 1000L) {
                service.onEvent(AgentService.EV_TPS, null, String.format(Locale.ROOT, "服务器卡顿：平均每 tick %.1f ms（约 %.1f TPS），已持续 %d 秒",
                        mspt, Math.min(20, 1000 / mspt), (now - lowSince) / 1000));
                lowSince = now;
            }
        } else {
            lowSince = 0;
        }
        if (tap != null) {
            LogTap.Line l;
            while ((l = tap.errors.poll()) != null) {
                service.onEvent(AgentService.EV_ERROR, null, "日志 " + l.level() + " [" + l.logger() + "] " + io.github.aftsakee.aiadmin.util.Text.brief(l.message(), 400));
            }
        }
    }
}
