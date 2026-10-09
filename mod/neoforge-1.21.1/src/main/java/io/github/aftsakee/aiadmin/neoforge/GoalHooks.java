package io.github.aftsakee.aiadmin.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameDataQueries;
import io.github.aftsakee.aiadmin.goals.GoalEngine;
import io.github.aftsakee.aiadmin.goals.GoalStore;
import io.github.aftsakee.aiadmin.goals.GoalTable;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 目标表的游戏侧：/goal 指令（所有玩家可用）、定期检查在线玩家的阶段/个人目标进度、定时私聊提示。只在专用服务器上启用。
 * 订阅和调用顺序见 {@link HookBus}：开服时排在 ServerHooks、TrackerHooks 之后、StoryHooks 之前（/goal project 挂在本类的 /goal 节点下）；
 * tick 由 HookBus 每 20 tick 调一次 {@link #onTick}。
 * <p>线程：全部在服务端主线程（tick、指令）；GoalEngine/GoalStore 自身是线程安全的，AI 工具在代理线程也会读写它们。
 * 例外（主线程上的文件 IO，core 的 GoalTable/GoalStore 行为）：每秒查一次 goals.json 的修改时间、改过就重读；每轮检查后有改动时
 * saveIfDirty 写一次 goals-progress.json；开服时生成默认目标表、读进度。
 * <p>开销：每 check_interval_seconds（默认 30 秒）对每个在线玩家做一次判定（读背包 + 几个进度/统计），
 * 只有到点要提示时才跑合成规划；游戏资料快照只在开服和 /reload 后重建。
 */
final class GoalHooks {
    private static MinecraftServer server;
    private static volatile GoalEngine engine;
    /**
     * 目标功能的文件 IO（goals.json 改没改、goals-progress.json 落盘）用的后台线程：项目规则要求主线程上不做文件 IO，
     * 以前每轮检查后在主线程写进度、每秒在主线程查 goals.json 的修改时间（改过就在主线程重读）。开服时建、关服时关。
     */
    private static volatile ExecutorService io;
    private static final Map<UUID, Long> ONLINE_SINCE = new ConcurrentHashMap<>();
    private static long lastPrune;
    private static long lastErrorLog;
    /** 已经把 warnings 写过日志的那份目标表（表按修改时间重读后是新对象，再写一次）。只在主线程读写。 */
    private static GoalTable.Table warnedTable;

    private GoalHooks() {}

    static GoalEngine engine() {
        return engine;
    }

    static void onStarted(ServerStartedEvent e) {
        if (!e.getServer().isDedicatedServer()) return;
        server = e.getServer();
        Path gameDir = FMLPaths.GAMEDIR.get();
        GoalTable table = new GoalTable(gameDir.resolve("config").resolve("aiadmin").resolve("goals.json"));
        try {
            if (table.ensure()) AiAdminMod.LOG.info("[AI管理] 已生成默认目标表 config/aiadmin/goals.json");
        } catch (java.io.IOException ex) {
            AiAdminMod.LOG.warn("[AI管理] 无法生成 goals.json：{}", ex.toString());
        }
        if (table.get() == null) AiAdminMod.LOG.warn("[AI管理] {}", table.error());
        else logTableWarnings(table.get());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aiadmin-goals-io");
            t.setDaemon(true);
            return t;
        });
        MinecraftServer srv = server;
        table.offMainThread(srv::isSameThread, io); // 上面开服时的首次读取在主线程上同步做（生命周期事件，只做一次）
        GoalStore store = new GoalStore(gameDir.resolve("aiadmin").resolve("goals-progress.json"));
        if (store.loadError() != null) AiAdminMod.LOG.warn("[AI管理] 目标进度：{}", store.loadError());
        engine = new GoalEngine(table, store, GameDataIndex::cached, mod -> ModList.get().isLoaded(mod));
        try {
            GameDataIndex.get(server); // 开服时建好快照，之后的 AI 查询和 /goal 不用等
        } catch (RuntimeException ex) {
            AiAdminMod.LOG.error("[AI管理] 游戏资料建立失败（配方/掉落查询不可用）", ex);
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) ONLINE_SINCE.putIfAbsent(p.getUUID(), now);
        if (Boolean.getBoolean("aiadmin.devGoalTest")) devTest();
    }

    /** goals.json 的非致命问题（字段类型不对、阶段缺 id）：每份表只写一次日志，服主改完保存后重读到新表时再写。 */
    private static void logTableWarnings(GoalTable.Table t) {
        if (t == warnedTable) return;
        warnedTable = t;
        for (String w : t.warnings()) AiAdminMod.LOG.warn("[AI管理] goals.json：{}", w);
    }

    static void onStopping(ServerStoppingEvent e) {
        ExecutorService ex = io;
        io = null;
        if (ex != null) ex.shutdown(); // 已排的落盘照常做完；下面这次在关服线程上同步写最后的进度
        if (engine != null) engine.store().saveIfDirty();
        engine = null;
        server = null;
        warnedTable = null;
        ONLINE_SINCE.clear();
        GameDataIndex.clear();
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        ONLINE_SINCE.put(e.getEntity().getUUID(), System.currentTimeMillis());
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        ONLINE_SINCE.remove(e.getEntity().getUUID());
    }

    /** 每 20 tick（每秒，HookBus 调用；tick 是 HookBus 的计数）：每秒看一次目标表，每 check_interval_seconds 秒检查一轮。 */
    static void onTick(MinecraftServer srv, long tick) {
        GoalEngine en = engine;
        if (en == null) return;
        GoalTable.Table t = en.table().get();
        if (t != null) logTableWarnings(t);
        if (t == null || !t.settings().enabled()) return;
        if ((tick / 20) % t.settings().checkIntervalSeconds() != 0) return;
        long now = System.currentTimeMillis();
        try {
            GameDataIndex.get(srv); // /reload 之后在这里重建
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (p instanceof FakePlayer) continue;
                GoalEngine.Facts f = facts(srv, p);
                GoalEngine.News n = en.check(f, now);
                for (String line : n.tell()) say(p, line, ChatFormatting.GOLD);
                for (String line : n.announce()) srv.getPlayerList().broadcastSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD), false);
                List<String> hint = en.hint(f, now);
                for (int i = 0; i < hint.size(); i++) say(p, hint.get(i), i == 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY);
            }
            saveLater(en); // 一轮检查写一次盘（以前每名玩家每次检查都写），写在后台线程上
            if (now - lastPrune > 3600_000L) {
                lastPrune = now;
                en.store().prune(now);
            }
        } catch (RuntimeException ex) {
            // 目标功能出错不能影响游戏；日志限频（每 10 分钟最多一条）
            if (now - lastErrorLog > 600_000L) {
                lastErrorLog = now;
                AiAdminMod.LOG.error("[AI管理] 目标检查出错", ex);
            }
        }
    }

    /** 把这一轮的进度交给后台线程落盘；执行器已关（正在关服）时不写，关服流程会同步写最后一次。 */
    private static void saveLater(GoalEngine en) {
        ExecutorService ex = io;
        if (ex == null) return;
        try {
            ex.execute(en.store()::saveIfDirty);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 正在关服：onStopping 会同步写
        }
    }

    // ———————————————————— 玩家事实 ————————————————————

    /** 背包 + 盔甲栏 + 副手，按物品 ID 汇总。主线程调用。 */
    static Map<String, Integer> inventory(ServerPlayer p) {
        Map<String, Integer> m = new HashMap<>();
        Inventory inv = p.getInventory();
        for (List<ItemStack> l : List.of(inv.items, inv.armor, inv.offhand)) {
            for (ItemStack s : l) if (!s.isEmpty()) m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        return m;
    }

    static GoalEngine.Facts facts(MinecraftServer srv, ServerPlayer p) {
        Map<String, Integer> inv = inventory(p);
        String name = McFmt.name(p);
        long since = ONLINE_SINCE.getOrDefault(p.getUUID(), System.currentTimeMillis());
        return new GoalEngine.Facts() {
            public String name() {
                return name;
            }

            public Map<String, Integer> inventory() {
                return inv;
            }

            public boolean hasAdvancement(String id) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl == null) return false;
                AdvancementHolder h = srv.getAdvancements().get(rl);
                if (h == null) return false;
                try {
                    return p.getAdvancements().getOrStartProgress(h).isDone();
                } catch (RuntimeException ex) {
                    return false;
                }
            }

            public int stat(String type, String key) {
                ResourceLocation t = ResourceLocation.tryParse(type);
                ResourceLocation k = ResourceLocation.tryParse(key);
                if (t == null || k == null) return 0;
                StatType<?> st = BuiltInRegistries.STAT_TYPE.get(t);
                return st == null ? 0 : statValue(p, st, k);
            }

            public long onlineSince() {
                return since;
            }
        };
    }

    private static <T> int statValue(ServerPlayer p, StatType<T> type, ResourceLocation key) {
        T obj = type.getRegistry().get(key);
        if (obj == null) return 0;
        try {
            return p.getStats().getValue(type, obj);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ———————————————————— /goal ————————————————————

    static void onRegisterCommands(RegisterCommandsEvent e) {
        register(e.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        var itemArg = Commands.argument("item", ResourceLocationArgument.id())
                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), b));
        d.register(Commands.literal("goal").requires(src -> engine != null)
                .executes(GoalHooks::show)
                .then(Commands.literal("next").executes(GoalHooks::next))
                .then(Commands.literal("stages").executes(GoalHooks::stages))
                .then(Commands.literal("plan").then(itemArg.executes(c -> plan(c, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000)).executes(c -> plan(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("add").then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), b))
                        .executes(c -> add(c, 1, ""))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100000)).executes(c -> add(c, IntegerArgumentType.getInteger(c, "count"), ""))
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(c -> add(c, IntegerArgumentType.getInteger(c, "count"), StringArgumentType.getString(c, "note")))))))
                .then(Commands.literal("remove").then(Commands.argument("index", IntegerArgumentType.integer(1, 100)).executes(GoalHooks::remove)))
                .then(Commands.literal("clear").executes(GoalHooks::clear))
                .then(Commands.literal("hints")
                        .then(Commands.literal("on").executes(c -> hints(c, true)))
                        .then(Commands.literal("off").executes(c -> hints(c, false))))
                .then(Commands.literal("find").then(Commands.argument("name", StringArgumentType.greedyString()).executes(GoalHooks::find))));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> c) {
        return Cmds.player(c);
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        GoalEngine en = engine;
        String name = McFmt.name(p);
        GoalTable.Stage cur = en.current(name);
        say(p, "【目标】当前阶段：" + (cur == null ? "主线全部完成！" : cur.name() + (cur.description().isBlank() ? "" : "——" + cur.description())), ChatFormatting.GOLD);
        List<GoalTable.Stage> side = en.sideGoals(name);
        if (!side.isEmpty()) say(p, "支线：" + String.join("、", side.stream().map(GoalTable.Stage::name).toList()), ChatFormatting.YELLOW);
        GameData d = GameDataIndex.cached();
        Map<String, Integer> inv = inventory(p);
        int i = 0;
        for (GoalStore.Personal x : GoalStore.ordered(en.store().get(name).personal)) {
            i++;
            String label = d == null ? x.item : d.label(x.item);
            String state = x.done != 0 ? "✔ 已完成" : GoalEngine.count(x.item, inv, d) + "/" + x.count;
            say(p, i + ". " + label + " ×" + x.count + "  " + state + (x.note == null || x.note.isBlank() ? "" : "（" + x.note + "）"), ChatFormatting.WHITE);
        }
        if (i == 0) say(p, "没有个人目标。/goal add <物品> [数量] 添加，或者直接跟 AI 说\"我想做 XX\"。", ChatFormatting.GRAY);
        say(p, "/goal next 下一步 · /goal plan <物品> 合成路线 · /goal stages 全部阶段 · /goal find <名字> 查物品 ID · /goal hints on|off",
                ChatFormatting.GRAY);
        return 1;
    }

    private static int next(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        List<String> lines = engine.nextLines(facts(c.getSource().getServer(), p), 8, 6);
        if (lines.isEmpty()) say(p, "目标都完成了！可以用 /goal add 给自己定个新目标。", ChatFormatting.GOLD);
        for (int i = 0; i < lines.size(); i++) say(p, lines.get(i), i == 0 ? ChatFormatting.AQUA : ChatFormatting.WHITE);
        return 1;
    }

    private static int stages(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        GoalStore.Player rec = engine.store().get(McFmt.name(p));
        GoalTable.Stage cur = engine.current(McFmt.name(p));
        for (GoalTable.Stage s : engine.stages()) {
            boolean done = rec.stagesDone.containsKey(s.id());
            String mark = done ? "✔ " : s == cur ? "➜ " : "  ";
            say(p, mark + s.name() + (s.optional() ? "（支线）" : "") + (s.description().isBlank() ? "" : "：" + s.description()),
                    done ? ChatFormatting.GREEN : s == cur ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        }
        return 1;
    }

    private static int plan(CommandContext<CommandSourceStack> c, int count) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        GameData d = GameDataIndex.get(c.getSource().getServer());
        String id = ResourceLocationArgument.getId(c, "item").toString();
        if (!d.items.containsKey(id)) {
            say(p, "没有这个物品：" + id + "。用 /goal find <名字> 查 ID。", ChatFormatting.RED);
            return 0;
        }
        CraftPlanner.Plan plan = CraftPlanner.plan(d, id, count, inventory(p), CraftPlanner.Options.defaults());
        List<String> lines = GameDataQueries.planLines(d, plan, 12, 8);
        for (int i = 0; i < lines.size(); i++) say(p, lines.get(i), i == 0 ? ChatFormatting.AQUA : ChatFormatting.WHITE);
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> c, int count, String note) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        GameData d = GameDataIndex.get(c.getSource().getServer());
        String id = ResourceLocationArgument.getId(c, "item").toString();
        if (!d.items.containsKey(id)) {
            say(p, "没有这个物品：" + id + "。用 /goal find <名字> 查 ID。", ChatFormatting.RED);
            return 0;
        }
        GoalTable.Table t = engine.table().get();
        String name = McFmt.name(p);
        String err = engine.store().addPersonal(name, id, count, note, name, t == null ? 10 : t.settings().personalMax(), System.currentTimeMillis());
        if (err != null) {
            say(p, err, ChatFormatting.RED);
            return 0;
        }
        say(p, "已记下目标：" + d.label(id) + " ×" + count + "。/goal plan " + id + " 看合成路线。", ChatFormatting.GREEN);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        GoalStore.Personal x = engine.store().removePersonal(McFmt.name(p), String.valueOf(IntegerArgumentType.getInteger(c, "index")));
        say(p, x == null ? "没有这个序号（/goal 看列表）" : "已删除：" + x.item + " ×" + x.count, x == null ? ChatFormatting.RED : ChatFormatting.GREEN);
        return x == null ? 0 : 1;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        say(p, "已清空 " + engine.store().clearPersonal(McFmt.name(p)) + " 个个人目标", ChatFormatting.GREEN);
        return 1;
    }

    private static int hints(CommandContext<CommandSourceStack> c, boolean on) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        engine.store().update(McFmt.name(p), q -> q.hintsOff = !on);
        say(p, on ? "已打开定时目标提示" : "已关闭定时目标提示（/goal hints on 重新打开）", ChatFormatting.GREEN);
        return 1;
    }

    private static int find(CommandContext<CommandSourceStack> c) {
        GameData d = GameDataIndex.get(c.getSource().getServer());
        String q = StringArgumentType.getString(c, "name");
        List<GameData.ItemInfo> hits = d.search(q, 8);
        ServerPlayer p = c.getSource().getPlayer();
        List<String> lines = new ArrayList<>();
        if (hits.isEmpty()) lines.add("没找到「" + q + "」。原版物品服务端只有英文名，试试英文名或 ID 片段。");
        for (GameData.ItemInfo i : hits) lines.add(i.id() + "  " + i.label());
        for (String l : lines) {
            if (p != null) say(p, l, hits.isEmpty() ? ChatFormatting.RED : ChatFormatting.WHITE);
            else c.getSource().sendSuccess(() -> Component.literal(l), false);
        }
        return hits.size();
    }

    private static void say(ServerPlayer p, String line, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(line).withStyle(color));
        if (p instanceof FakePlayer) AiAdminMod.LOG.info("[E2E-GOAL] {}", line); // 开发自测：假玩家收不到消息，写日志供 e2e 检查
    }

    // ———————————————————— 开发自测 ————————————————————

    /**
     * 仅开发/端到端测试（-Daiadmin.devGoalTest=true）：假玩家 E2EGoal 带着 2 个橡木原木、1 个粗铁，
     * 依次执行 /goal add、/goal、/goal plan、/goal next、/goal stages、/goal find，再跑一次判定和提示（忽略上线时长），
     * 输出都以 [E2E-GOAL] 写进日志，由 scripts/e2e_gamedata.py 检查。
     */
    private static void devTest() {
        net.minecraft.server.level.ServerLevel lvl = server.overworld();
        FakePlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(lvl,
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes("E2EGoal".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "E2EGoal"));
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
        fp.getInventory().add(new ItemStack(net.minecraft.world.item.Items.RAW_IRON, 1));
        engine.store().clearPersonal("E2EGoal");
        engine.store().update("E2EGoal", q -> {
            q.stagesDone.clear();
            q.lastHint = 0;
            q.lastHintKey = null;
            q.hintsOff = false;
        });
        CommandSourceStack src = fp.createCommandSourceStack().withPermission(0);
        for (String cmd : List.of("goal add minecraft:torch 8 照明用", "goal", "goal plan minecraft:iron_pickaxe", "goal next", "goal stages",
                "goal find iron ingot", "goal remove 1", "goal next")) {
            AiAdminMod.LOG.info("[E2E-GOAL] > /{}", cmd);
            server.getCommands().performPrefixedCommand(src.withCallback((ok, r) -> {}), cmd);
        }
        GoalEngine.Facts f = facts(server, fp);
        GoalEngine.Facts early = new GoalEngine.Facts() {
            public String name() {
                return f.name();
            }

            public Map<String, Integer> inventory() {
                return f.inventory();
            }

            public boolean hasAdvancement(String id) {
                return f.hasAdvancement(id);
            }

            public int stat(String type, String key) {
                return f.stat(type, key);
            }

            public long onlineSince() {
                return 0; // 假装上线很久了
            }
        };
        long now = System.currentTimeMillis();
        for (String l : engine.check(early, now).tell()) AiAdminMod.LOG.info("[E2E-GOAL] check: {}", l);
        List<String> hint = new ArrayList<>(engine.hint(early, now));
        for (String l : hint) AiAdminMod.LOG.info("[E2E-GOAL] hint: {}", l);
        AiAdminMod.LOG.info("[E2E-GOAL] hint-again-empty: {}", engine.hint(early, now + 1000).isEmpty());
        AiAdminMod.LOG.info("[E2E-GOAL] done");
    }
}
