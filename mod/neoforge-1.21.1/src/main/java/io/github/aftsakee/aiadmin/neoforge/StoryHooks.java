package io.github.aftsakee.aiadmin.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.CombatLog;
import io.github.aftsakee.aiadmin.agent.ProjectStore;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 有意义的行为和战斗前因后果（MC 侧采集，数据存在 AgentService 的 CombatLog / Milestones / ProjectStore / BuildLedger）：
 * <ul>
 *   <li>受伤：记谁打的、用什么、打完剩多少血（给死亡记录和"救下队友"用）；Boss 记一起打的玩家。</li>
 *   <li>死亡：整理死前 30 秒、附近队友、掉落件数，附在死亡事件里；之后跟踪是否回去捡（捡回 / 没捡回各发一次跟进事件）。</li>
 *   <li>首次造出机器或重要方块、首次捡到稀有物品、驯服、击败 Boss、救下队友、工程放方块跨过里程碑 → milestone 事件（每人每类单独冷却）。</li>
 *   <li>放方块记进建筑账本（回答"这栋房子是谁的"）。</li>
 *   <li>/goal project …：玩家自己的工程；工具 get_death_story、list_projects、set_project、who_built。</li>
 * </ul>
 * 订阅、优先级和调用顺序见 {@link HookBus}（伤害、死亡、拾取、合成、放方块和 TrackerHooks 共用一次订阅，排在它后面）；
 * 物品/实体显示名、维度、坐标的格式见 {@link McFmt}。
 * <p>线程：事件处理在服务端主线程，基本只做内存操作（里程碑、建筑账本、放方块计数只标脏，由 AgentService 的定时器落盘）。已知例外（core 侧行为）：
 * 放方块让工程跨过里程碑时 ProjectStore.placed 会立即写一次 projects.json；/goal project 的增删改指令立即写盘；
 * 关服时 {@link #onStopping} 兜底落盘。工具（get_death_story、list_projects…）在代理/远程线程上调用，只经过各状态仓库的同步方法和快照。
 * 只在机器人启动后（专用服务器）生效。
 */
final class StoryHooks {
    private StoryHooks() {}

    /** 原版里值得庆祝的"第一次造出"（物品 id 的 path）。 */
    static final Set<String> VANILLA_CRAFTS = Set.of("beacon", "enchanting_table", "anvil", "brewing_stand", "conduit", "ender_chest", "respawn_anchor",
            "lodestone", "end_crystal", "netherite_sword", "netherite_pickaxe", "netherite_axe", "netherite_shovel", "netherite_hoe", "netherite_helmet",
            "netherite_chestplate", "netherite_leggings", "netherite_boots", "shulker_box", "crafter", "recovery_compass", "mace", "heavy_core");
    /** 原版里值得一提的"第一次捡到"。 */
    static final Set<String> VANILLA_FINDS = Set.of("diamond", "ancient_debris", "netherite_scrap", "netherite_ingot", "nether_star", "dragon_egg", "elytra",
            "totem_of_undying", "heart_of_the_sea", "trident", "enchanted_golden_apple", "echo_shard", "sniffer_egg", "wither_skeleton_skull", "dragon_head",
            "shulker_shell", "heavy_core", "trial_key", "ominous_trial_key");
    /** 模组里大量合成的管线类方块（第一次造也不算里程碑，免得早期刷屏）。 */
    static final Pattern BULK = Pattern.compile("(cable|pipe|wire|duct|conveyor|belt|conduit|rail|glass|slab|stairs|wall|fence|panel|frame|casing|scaffold)");
    /** Boss 实体 UUID → 打过它的玩家（只保留最近 50 个 Boss）。 */
    private static final Map<UUID, Set<String>> BOSS_FIGHTERS = new LinkedHashMap<>();

    /**
     * 仅开发/端到端测试（-Daiadmin.devSocialTest=true）：假玩家 E2ESocial 走真实的指令和事件路径——
     * /msg（私聊脱敏）、/ai（私聊机器人）、/goal project add、放 100 个方块（工程里程碑 + 建筑账本）、造信标、捡钻石、驯服狼、
     * 死亡（前因后果）、回死亡点捡东西（跟进事件）。结果由 scripts/e2e_social.py 经远程接口和假模型的请求记录检查。
     * 假玩家不能真的挨打、死亡（FakePlayer 免伤、die 是空实现），所以死亡事件直接投递 LivingDeathEvent。
     */
    static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!Boolean.getBoolean("aiadmin.devSocialTest") || svc() == null) return;
        MinecraftServer server = e.getServer();
        net.minecraft.server.level.ServerLevel lvl = server.overworld();
        net.neoforged.neoforge.common.util.FakePlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(lvl,
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes("E2ESocial".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "E2ESocial"));
        fp.setPos(0.5, 120, 0.5);
        CommandSourceStack src = fp.createCommandSourceStack().withPermission(0).withCallback((ok, r) -> {});
        try {
            for (String cmd : List.of("msg Steve 秘密内容123", "ai 钻石一般在哪一层", "goal project add build 测试酒馆")) {
                AiAdminMod.LOG.info("[E2E-SOCIAL] > /{}", cmd);
                server.getCommands().performPrefixedCommand(src, cmd);
            }
            for (int i = 0; i < 100; i++) {
                BlockPos pos = new BlockPos(2 + i % 10, 120, 2 + i / 10);
                lvl.setBlock(pos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                net.neoforged.neoforge.event.EventHooks.onBlockPlace(fp, net.neoforged.neoforge.common.util.BlockSnapshot.create(lvl.dimension(), lvl, pos),
                        net.minecraft.core.Direction.UP);
            }
            net.neoforged.neoforge.event.EventHooks.firePlayerCraftingEvent(fp, new ItemStack(net.minecraft.world.item.Items.BEACON),
                    new net.minecraft.world.SimpleContainer(9));
            var diamond = new net.minecraft.world.entity.item.ItemEntity(lvl, 50.5, 120, 50.5, new ItemStack(net.minecraft.world.item.Items.DIAMOND));
            net.neoforged.neoforge.event.EventHooks.fireItemPickupPost(diamond, fp, diamond.getItem().copy());
            var wolf = net.minecraft.world.entity.EntityType.WOLF.create(lvl);
            if (wolf != null) NeoForge.EVENT_BUS.post(new AnimalTameEvent(wolf, fp));
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(fp, lvl.damageSources().fall()));
            var junk = new net.minecraft.world.entity.item.ItemEntity(lvl, 1.5, 120, 1.5, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 12));
            net.neoforged.neoforge.event.EventHooks.fireItemPickupPost(junk, fp, junk.getItem().copy());
            AiAdminMod.LOG.info("[E2E-SOCIAL] done");
        } catch (RuntimeException ex) {
            AiAdminMod.LOG.error("[E2E-SOCIAL] 开发自测失败", ex);
        }
    }

    private static AgentService svc() {
        return ServerHooks.service();
    }

    private static void milestone(String player, String category, String text) {
        AgentService s = svc();
        if (s == null) return;
        ServerHooks.activity().add("milestone", player, text);
        s.onEvent(AgentService.EV_MILESTONE, player + "|" + category, text);
    }

    // ———————————————————— 战斗 ————————————————————

    static void onDamage(LivingDamageEvent.Post e) {
        AgentService s = svc();
        if (s == null || e.getNewDamage() <= 0) return;
        Entity src = e.getSource().getEntity();
        if (Players.real(e.getEntity()) instanceof ServerPlayer p) {
            String weapon = null;
            if (src instanceof LivingEntity le && !le.getMainHandItem().isEmpty()) weapon = McFmt.label(le.getMainHandItem());
            String attackerName = src == null ? null : src instanceof Player pl ? McFmt.name(pl) : src.hasCustomName() ? src.getCustomName().getString() : null;
            s.combat().hurt(McFmt.name(p), new CombatLog.Hit(System.currentTimeMillis(), e.getNewDamage(), e.getSource().getMsgId(),
                    src == null ? null : McFmt.id(src), attackerName, src == null ? null : src.getUUID().toString(), src instanceof Player, weapon,
                    p.getHealth(), p.getMaxHealth()));
        } else if (Players.real(src) instanceof ServerPlayer attacker && e.getEntity().getType().is(Tags.EntityTypes.BOSSES)) {
            BOSS_FIGHTERS.computeIfAbsent(e.getEntity().getUUID(), k -> new LinkedHashSet<>()).add(McFmt.name(attacker));
            while (BOSS_FIGHTERS.size() > 50) BOSS_FIGHTERS.remove(BOSS_FIGHTERS.keySet().iterator().next());
        }
    }

    /** 玩家死亡（ServerHooks.onDeath 调用）：记一次死亡并返回前因后果，附在死亡事件里。 */
    static String playerDeath(ServerPlayer p, String message) {
        AgentService s = svc();
        if (s == null) return "";
        long now = System.currentTimeMillis();
        List<CombatLog.Nearby> near = new ArrayList<>();
        for (ServerPlayer o : p.serverLevel().players()) {
            if (o == p) continue;
            double d = o.distanceTo(p);
            if (d <= 32) near.add(new CombatLog.Nearby(McFmt.name(o), (int) Math.round(d), o.getHealth(), s.combat().hurtRecently(McFmt.name(o), now)));
        }
        near.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        boolean keep = p.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        CombatLog.Death d = s.combat().death(McFmt.name(p), message, McFmt.dimId(p), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                near.size() > 6 ? near.subList(0, 6) : near, keep, now);
        return CombatLog.story(d);
    }

    /** 订阅时收已取消的（见 HookBus）：墓碑类模组会取消掉落事件（物品进了墓碑），不收的话 drops 停在 -1，5 分钟后被误报"没回去捡"。 */
    static void onDrops(LivingDropsEvent e) {
        AgentService s = svc();
        if (s == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        int n = 0;
        if (!e.isCanceled()) for (var it : e.getDrops()) n += it.getItem().getCount(); // 被取消 = 进了墓碑之类，按 0 件记
        s.combat().drops(McFmt.name(p), n, System.currentTimeMillis());
    }

    /** 非玩家生物死亡：击败 Boss、救下队友。 */
    static void onEntityDeath(LivingDeathEvent e) {
        AgentService s = svc();
        if (s == null || e.getEntity() instanceof Player) return;
        LivingEntity victim = e.getEntity();
        Entity src = e.getSource().getEntity();
        long now = System.currentTimeMillis();
        if (victim.getType().is(Tags.EntityTypes.BOSSES)) {
            Set<String> fighters = BOSS_FIGHTERS.remove(victim.getUUID());
            if (fighters == null) fighters = new LinkedHashSet<>();
            if (Players.real(src) instanceof ServerPlayer killer) fighters.add(McFmt.name(killer));
            if (!fighters.isEmpty()) {
                String key = "boss:" + McFmt.id(victim);
                String first = s.milestones().serverFirst(key) ? "（全服第一次）" : "";
                String lead = fighters.iterator().next();
                for (String f : fighters) s.milestones().first(f, key);
                milestone(lead, "boss", String.join("、", fighters) + " 击败了 " + McFmt.label(victim) + first + "（" + McFmt.where(victim) + "）");
            }
            return;
        }
        if (Players.real(src) instanceof ServerPlayer killer) {
            String k = McFmt.name(killer);
            List<String> saved = s.combat().rescued(k, victim.getUUID().toString(), now);
            if (!saved.isEmpty()) {
                milestone(k, "rescue", k + " 救下了 " + String.join("、", saved) + "：击杀了正在攻击的 " + McFmt.label(victim));
            }
        }
    }

    // ———————————————————— 第一次 ————————————————————

    static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        AgentService s = svc();
        if (s == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p) || e.getCrafting().isEmpty()) return;
        ItemStack st = e.getCrafting();
        String why = notableCraft(st);
        if (why == null) return;
        String id = McFmt.id(st);
        String name = McFmt.name(p);
        if (!s.milestones().first(name, "craft:" + id)) return;
        boolean serverFirst = s.milestones().serverFirst("craft:" + id);
        milestone(name, "craft", name + (serverFirst ? " 全服第一个" : " 第一次") + "造出了 " + McFmt.label(st) + "（" + why + "）");
    }

    /** 值得记的合成：原版重要方块/下界合金装备、模组的机器（带方块实体的方块，排除管线类）、稀有度 ≥ 稀有的物品。 */
    static String notableCraft(ItemStack st) {
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
        String path = rl.getPath();
        if (rl.getNamespace().equals("minecraft")) {
            if (VANILLA_CRAFTS.contains(path) || path.endsWith("_shulker_box")) return "重要方块/装备";
            return st.getRarity() == Rarity.EPIC ? "史诗物品" : null;
        }
        if (st.getItem() instanceof BlockItem bi && bi.getBlock() instanceof EntityBlock && !BULK.matcher(path).find()) return "模组机器/方块";
        return st.getRarity() == Rarity.RARE || st.getRarity() == Rarity.EPIC ? "稀有物品" : null;
    }

    static void onPickup(ItemEntityPickupEvent.Post e) {
        AgentService s = svc();
        if (s == null || !(Players.real(e.getPlayer()) instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        String name = McFmt.name(p);
        ItemStack st = e.getOriginalStack();
        var ie = e.getItemEntity();
        // 回到死亡点捡东西
        CombatLog.Death d = s.combat().pickup(name, McFmt.dimId(p), ie.getX(), ie.getY(), ie.getZ(), st.getCount(), now);
        if (d != null) {
            ServerHooks.activity().add("death_followup", name, "回到死亡地点捡回了东西");
            s.onEvent(AgentService.EV_DEATH_FOLLOWUP, name, name + " 回到死亡地点（" + d.dim + " " + d.x + " " + d.y + " " + d.z + "）开始捡回掉落的东西，死后 "
                    + Math.max(1, (now - d.time) / 60_000L) + " 分钟（死因：" + d.message + "）");
        } else {
            s.combat().morePickup(name, McFmt.dimId(p), ie.getX(), ie.getY(), ie.getZ(), st.getCount(), now);
        }
        if (st.isEmpty()) return;
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
        boolean rare = (rl.getNamespace().equals("minecraft") && (VANILLA_FINDS.contains(rl.getPath()) || rl.getPath().startsWith("music_disc_")))
                || st.getRarity() == Rarity.EPIC || (!rl.getNamespace().equals("minecraft") && st.getRarity() == Rarity.RARE);
        if (!rare || !s.milestones().first(name, "find:" + rl)) return;
        boolean serverFirst = s.milestones().serverFirst("find:" + rl);
        milestone(name, "find", name + (serverFirst ? " 全服第一个" : " 第一次") + "捡到了 " + McFmt.label(st) + "（" + McFmt.where(p) + "）");
    }

    static void onTame(AnimalTameEvent e) {
        if (svc() == null || !(Players.real(e.getTamer()) instanceof ServerPlayer p)) return;
        String name = McFmt.name(p);
        milestone(name, "tame", name + " 驯服了 " + McFmt.label(e.getAnimal()));
    }

    // ———————————————————— 建筑 ————————————————————

    static void onPlace(BlockEvent.EntityPlaceEvent e) {
        AgentService s = svc();
        if (s == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        BlockPos pos = e.getPos();
        String name = McFmt.name(p);
        String d = McFmt.dimId(p);
        s.ledger().place(name, d, pos.getX(), pos.getZ());
        for (ProjectStore.Progress pr : s.projects().placed(name, d, pos.getX(), pos.getZ())) {
            milestone(pr.project().owner, "project", "工程「" + pr.project().title + "」范围内已经放了 " + pr.milestone() + " 个方块（" + ProjectStore.line(pr.project()) + "）");
        }
    }

    /** 每 200 tick（10 秒，HookBus 调用）：死后 5 分钟没回去捡的发跟进事件。 */
    static void onTick(MinecraftServer server, long tick) {
        AgentService s = svc();
        if (s == null) return;
        long now = System.currentTimeMillis();
        for (CombatLog.Death d : s.combat().expire(now)) {
            ServerHooks.activity().add("death_followup", d.player, "没回去捡，掉落物大概已消失");
            s.onEvent(AgentService.EV_DEATH_FOLLOWUP, d.player, d.player + " 死后 5 分钟没回去捡，掉落的 " + Math.max(d.drops, 0) + " 件东西大概已经消失（死因：" + d.message
                    + "，" + d.dim + " " + d.x + " " + d.y + " " + d.z + "）");
        }
    }

    static void onStopping(ServerStoppingEvent e) {
        // 通常 ServerHooks.onStopping 先运行、svc() 已经是 null，真正的落盘在 AgentService.stop() 里；这里只是兜底
        AgentService s = svc();
        if (s != null) s.saveState();
        BOSS_FIGHTERS.clear();
    }

    // ———————————————————— /goal project ————————————————————

    static void onRegisterCommands(RegisterCommandsEvent e) {
        register(e.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("goal").then(Commands.literal("project").requires(src -> svc() != null)
                .executes(StoryHooks::listMine)
                .then(Commands.literal("add").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ProjectStore.KINDS, b))
                        .then(Commands.argument("title", StringArgumentType.greedyString()).executes(StoryHooks::add))))
                .then(Commands.literal("note").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> byId(c, "note")))))
                .then(Commands.literal("done").then(Commands.argument("id", StringArgumentType.word()).executes(c -> byId(c, "done"))))
                .then(Commands.literal("drop").then(Commands.argument("id", StringArgumentType.word()).executes(c -> byId(c, "abandoned"))))
                .then(Commands.literal("join").then(Commands.argument("id", StringArgumentType.word()).executes(c -> byId(c, "join"))))
                .then(Commands.literal("leave").then(Commands.argument("id", StringArgumentType.word()).executes(c -> byId(c, "leave"))))
                .then(Commands.literal("here").executes(StoryHooks::here))
                .then(Commands.literal("radius").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(8, 256)).executes(StoryHooks::radius))))));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> c) {
        return Cmds.player(c);
    }

    private static int say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int listMine(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        List<ProjectStore.Project> l = svc().projects().forPlayer(McFmt.name(p), true);
        if (l.isEmpty()) {
            return say(c, "你没有进行中的工程。/goal project add <build|boss|explore|farm|other> <名字> 开一个（以你现在站的位置为中心）");
        }
        StringBuilder sb = new StringBuilder("你的工程：");
        for (ProjectStore.Project x : l) sb.append("\n").append(ProjectStore.line(x));
        sb.append("\n/goal project note <编号> <进展> | done <编号> | drop <编号> | join <编号> | here");
        return say(c, sb.toString());
    }

    private static int add(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        String kind = StringArgumentType.getString(c, "kind").toLowerCase(Locale.ROOT);
        if (!ProjectStore.KINDS.contains(kind)) {
            c.getSource().sendFailure(Component.literal("类型只能是 build（建筑）/ boss / explore（探索）/ farm（农场机器）/ other"));
            return 0;
        }
        String n = McFmt.name(p);
        return say(c, svc().projects().create(n, StringArgumentType.getString(c, "title"), kind, McFmt.dimId(p), p.getBlockX(), p.getBlockY(), p.getBlockZ(), 48, n));
    }

    private static int byId(CommandContext<CommandSourceStack> c, String action) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        String n = McFmt.name(p);
        String id = StringArgumentType.getString(c, "id");
        ProjectStore ps = svc().projects();
        ProjectStore.Project pr = ps.get(id);
        if (pr == null) {
            c.getSource().sendFailure(Component.literal("没有这个工程：" + id));
            return 0;
        }
        boolean mine = pr.owner.equalsIgnoreCase(n) || pr.members.stream().anyMatch(m -> m.equalsIgnoreCase(n));
        if (!mine && !action.equals("join")) {
            c.getSource().sendFailure(Component.literal("这不是你的工程（负责人 " + pr.owner + "），可以 /goal project join " + pr.id + " 加入"));
            return 0;
        }
        if (action.equals("abandoned") && !pr.owner.equalsIgnoreCase(n)) {
            c.getSource().sendFailure(Component.literal("只有负责人能放弃工程，成员可以 /goal project leave " + pr.id));
            return 0;
        }
        return say(c, switch (action) {
            case "note" -> ps.note(id, n, StringArgumentType.getString(c, "text"));
            case "join" -> ps.join(id, n);
            case "leave" -> ps.leave(id, n);
            default -> ps.status(id, action, n);
        });
    }

    private static int radius(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        ProjectStore.Project pr = svc().projects().get(StringArgumentType.getString(c, "id")); // 快照，只用来判断和显示
        if (pr == null || !pr.owner.equalsIgnoreCase(McFmt.name(p))) {
            c.getSource().sendFailure(Component.literal("没有这个工程，或你不是负责人"));
            return 0;
        }
        // 改动走 ProjectStore 的同步方法（以前在锁外直接改对象字段，对定时落盘线程不保证可见）
        return say(c, svc().projects().setRadius(pr.id, McFmt.name(p), IntegerArgumentType.getInteger(c, "blocks")));
    }

    private static int here(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = player(c);
        if (p == null) return 0;
        return say(c, whoBuilt(svc(), McFmt.dimId(p), p.getBlockX(), p.getBlockZ()).get("summary").getAsString());
    }

    // ———————————————————— 工具 ————————————————————

    static JsonObject whoBuilt(AgentService s, String dim, int x, int z) {
        JsonObject o = s.ledger().who(dim, x, z, 1);
        o.add("projects_here", ProjectStore.json(s.projects().at(dim, x, z)));
        StringBuilder sb = new StringBuilder();
        for (ProjectStore.Project pr : s.projects().at(dim, x, z)) sb.append("这里是工程").append(ProjectStore.line(pr)).append("。");
        var b = o.getAsJsonArray("builders");
        if (b.isEmpty()) sb.append("附近没有记录到谁放过方块（账本从 1.0.0.22 起才开始记）。");
        else {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < b.size() && i < 4; i++) {
                JsonObject e = b.get(i).getAsJsonObject();
                l.add(e.get("player").getAsString() + " " + e.get("blocks").getAsLong() + " 块（" + e.get("share").getAsDouble() + "%）");
            }
            sb.append("附近放方块最多的：").append(String.join("、", l)).append("（只是参考）。");
        }
        o.addProperty("summary", sb.toString());
        return o;
    }

    static void registerTools(Tools t, MinecraftServer server, McBridge bridge) {
        t.add(Tools.schema("get_death_story",
                        "一名玩家最近几次死亡的前因后果：死前 30 秒被谁、用什么、打了多少（每一下和打完剩多少血）、附近有哪些队友（是否也在挨打）、"
                                + "掉了多少东西、之后有没有回去捡回。吐槽\"组队翻车\"或安慰之前先查清楚，不要只凭一句死亡提示猜故事。")
                        .str("player", "玩家名", true)
                        .integer("count", "最近几次，默认 3，最多 10", false).build(),
                (a, ctx) -> {
                    AgentService s = svc();
                    if (s == null) return "{\"error\":\"机器人还没启动\"}";
                    JsonObject o = new JsonObject();
                    o.addProperty("player", Tools.optStr(a, "player", ""));
                    o.add("deaths", s.combat().json(Tools.optStr(a, "player", "").trim(), Tools.optInt(a, "count", 3, 1, 10)));
                    if (o.getAsJsonArray("deaths").isEmpty()) o.addProperty("note", "本次开服以来没有这名玩家的死亡记录（死亡记录只在内存里，重启清空）");
                    return o.toString();
                });
        t.add(Tools.schema("list_projects",
                        "玩家的工程和打算（\"建酒馆\"\"准备打凋灵\"\"找末地城\"）：标题、类型、负责人和成员、地点、范围内已放方块数、最近进展。"
                                + "可以围绕玩家正在做的事接话、问进展。")
                        .str("player", "只看这名玩家参与的（可选）", false)
                        .strEnum("status", "状态，默认 active", false, "active", "done", "abandoned", "all").build(),
                (a, ctx) -> {
                    AgentService s = svc();
                    if (s == null) return "{\"error\":\"机器人还没启动\"}";
                    String pl = Tools.optStr(a, "player", "").trim();
                    String st = Tools.optStr(a, "status", "active");
                    List<ProjectStore.Project> l = new ArrayList<>();
                    for (ProjectStore.Project p : pl.isEmpty() ? s.projects().all(st) : s.projects().forPlayer(pl, false)) {
                        if (st.equals("all") || p.status.equals(st)) l.add(p);
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("count", l.size());
                    o.add("projects", ProjectStore.json(l.size() > 30 ? l.subList(l.size() - 30, l.size()) : l));
                    return o.toString();
                });
        t.add(Tools.schema("set_project",
                        "记下或更新玩家的工程。玩家说\"今天要建酒馆\"\"我们去打凋灵\"时用 create（地点默认是他现在的位置）；说起进展时用 note；做完了用 done。"
                                + "玩家聊天唤醒你时只能替正在说话的玩家记（create 的负责人就是他，note/done 要是他参与的工程）；事件和巡检里只能 note。")
                        .strEnum("action", "要做什么", true, "create", "note", "done", "abandon", "join")
                        .str("player", "玩家名（create 的负责人；其他操作的操作者）", true)
                        .str("id", "工程编号（note/done/abandon/join 时需要），如 p3", false)
                        .str("title", "工程名（create 时需要，40 字以内）", false)
                        .strEnum("kind", "类型（create 时，默认 build）", false, "build", "boss", "explore", "farm", "other")
                        .str("text", "进展（note 时需要，120 字以内）", false).build(),
                (a, ctx) -> setProject(server, bridge, a, ctx));
        t.add(Tools.schema("who_built",
                        "这一带是谁建的：覆盖这个位置的工程，以及附近 3×3 区块里谁放的方块最多（按放方块数推断，只是参考；账本从 1.0.0.22 起才开始记）。"
                                + "给 player 就查他现在站的地方，否则给 x、z（和 dim，默认主世界）。")
                        .str("player", "以这名在线玩家的位置为准（可选）", false)
                        .integer("x", "X 坐标（可选）", false)
                        .integer("z", "Z 坐标（可选）", false)
                        .str("dim", "维度，如 minecraft:overworld、minecraft:the_nether（可选）", false).build(),
                (a, ctx) -> {
                    AgentService s = svc();
                    if (s == null) return "{\"error\":\"机器人还没启动\"}";
                    String pl = Tools.optStr(a, "player", "").trim();
                    if (!pl.isEmpty()) {
                        int[] pos = bridge.onServer(() -> {
                            ServerPlayer p = server.getPlayerList().getPlayerByName(pl);
                            return p == null ? null : new int[]{p.getBlockX(), p.getBlockZ()};
                        });
                        String d = bridge.onServer(() -> {
                            ServerPlayer p = server.getPlayerList().getPlayerByName(pl);
                            return p == null ? null : McFmt.dimId(p);
                        });
                        if (pos == null) return "{\"error\":\"玩家不在线：" + pl + "\"}";
                        return whoBuilt(s, d, pos[0], pos[1]).toString();
                    }
                    if (!a.has("x") || !a.has("z")) return "{\"error\":\"需要 player，或者 x 和 z\"}";
                    String d = Tools.optStr(a, "dim", "minecraft:overworld");
                    return whoBuilt(s, d.contains(":") ? d : "minecraft:" + d, Tools.optInt(a, "x", 0, -30_000_000, 30_000_000),
                            Tools.optInt(a, "z", 0, -30_000_000, 30_000_000)).toString();
                });
    }

    static String setProject(MinecraftServer server, McBridge bridge, JsonObject a, RunContext ctx) {
        AgentService s = svc();
        JsonObject o = new JsonObject();
        if (s == null) {
            o.addProperty("error", "机器人还没启动");
            return o.toString();
        }
        String action = Tools.optStr(a, "action", "");
        String player = Tools.optStr(a, "player", "").trim();
        Trigger tr = ctx.trigger;
        if (tr.kind() == Trigger.Kind.REMOTE && !ctx.cfg.remote.allowActions) {
            o.addProperty("error", "远程接口没有开放动作（remote.allow_actions=false）");
            return o.toString();
        }
        // 只认真正找机器人（@ 或 /ai 私聊）的人：同批里没叫机器人的公屏发言者不算（见 SocialTools.set 的同一条约束）
        if (tr.kind() == Trigger.Kind.CHAT && !tr.direct().contains(player.toLowerCase(java.util.Locale.ROOT))) {
            o.addProperty("error", "玩家聊天唤醒时只能替 @ 了机器人（或私聊它）的玩家自己记工程");
            return o.toString();
        }
        boolean observe = tr.kind() == Trigger.Kind.EVENT || tr.kind() == Trigger.Kind.PATROL;
        if (observe && !action.equals("note")) {
            o.addProperty("error", "事件和巡检里只能给已有工程记进展（note），开工、完成要玩家自己说");
            return o.toString();
        }
        ProjectStore ps = s.projects();
        String by = tr.kind() == Trigger.Kind.CHAT ? player : "AI（" + tr.label() + "）";
        if (action.equals("create")) {
            String[] where = bridge.onServer(() -> {
                ServerPlayer p = server.getPlayerList().getPlayerByName(player);
                return p == null ? null : new String[]{McFmt.dimId(p), String.valueOf(p.getBlockX()), String.valueOf(p.getBlockY()), String.valueOf(p.getBlockZ())};
            });
            String r = where == null
                    ? ps.create(player, Tools.optStr(a, "title", ""), Tools.optStr(a, "kind", "build"), null, 0, 0, 0, 48, by)
                    : ps.create(player, Tools.optStr(a, "title", ""), Tools.optStr(a, "kind", "build"), where[0], Integer.parseInt(where[1]),
                    Integer.parseInt(where[2]), Integer.parseInt(where[3]), 48, by);
            ctx.action(r);
            o.addProperty("result", r);
            return o.toString();
        }
        ProjectStore.Project pr = ps.get(Tools.optStr(a, "id", ""));
        if (pr == null) {
            o.addProperty("error", "没有这个工程：" + Tools.optStr(a, "id", "") + "（先用 list_projects 查编号）");
            return o.toString();
        }
        boolean involved = pr.owner.equalsIgnoreCase(player) || pr.members.stream().anyMatch(m -> m.equalsIgnoreCase(player));
        if (tr.kind() == Trigger.Kind.CHAT && !involved && !action.equals("join")) {
            o.addProperty("error", player + " 不在这个工程里（负责人 " + pr.owner + "）");
            return o.toString();
        }
        String r = switch (action) {
            case "note" -> ps.note(pr.id, by, Tools.optStr(a, "text", ""));
            case "done" -> ps.status(pr.id, "done", by);
            case "abandon" -> pr.owner.equalsIgnoreCase(player) || tr.kind() != Trigger.Kind.CHAT ? ps.status(pr.id, "abandoned", by) : "只有负责人能放弃工程";
            case "join" -> ps.join(pr.id, player);
            default -> "action 只能是 create / note / done / abandon / join";
        };
        ctx.action(r);
        o.addProperty("result", r);
        return o.toString();
    }
}
