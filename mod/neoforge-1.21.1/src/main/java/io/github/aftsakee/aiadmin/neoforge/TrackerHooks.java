package io.github.aftsakee.aiadmin.neoforge;

import io.github.aftsakee.aiadmin.agent.PlayerTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 把玩家行为喂给 {@link PlayerTracker}（只在专用服务器上）。订阅、优先级和调用顺序见 {@link HookBus}；名字、坐标、显示名的格式见 {@link McFmt}。
 * <ul>
 *   <li>挖/放方块、交互、丢物品按最低优先级监听、不接收已取消的事件——被领地/保护模组拦下的不算；</li>
 *   <li>每 {@link #SAMPLE_TICKS} tick 采样一次位置等状态，每 {@link #INVENTORY_TICKS} tick 记一次背包计数并清理过期记录（周期由 HookBus 的计数器驱动）；</li>
 *   <li>交互：右键"能交互"的方块（有方块实体，或方块类自己实现了右键逻辑——门、拉杆、按钮、模组机器…）记为 use，
 *       右键实体记为 interact_entity，都带名字（服务端的英文名）、注册 id 和所属模组，模组方块也能看出是什么；
 *       只是对着普通方块右键放方块的不算（放方块另有 place）；</li>
 *   <li>容器：打开时给非玩家背包的格子拍快照，关闭时对比，记下"从哪个容器拿走/放入了什么"；</li>
 *   <li>另记村民交易、丢物品、贵重矿石（带坐标）、危险物品（岩浆桶、打火石、TNT…）。</li>
 * </ul>
 * 只监听主手（副手的同一次右键会再触发一遍事件）；模组的假玩家（机械手等）不算（{@link Players#real}）。
 * <p>线程：事件处理全部在服务端主线程，只做计数/追加（内存），不阻塞；例外是开发自测（-Daiadmin.devFakePlayer）会写一份离线存档。
 */
final class TrackerHooks {
    static final int HISTORY_MINUTES = 60;
    static final int SAMPLE_TICKS = 200;     // 10 秒
    static final int INVENTORY_TICKS = 1200; // 1 分钟
    private static volatile PlayerTracker tracker;
    /** 手持这些物品右键/放下记为危险操作（id 的 path 部分）。 */
    static final Set<String> DANGER_ITEMS = Set.of("lava_bucket", "flint_and_steel", "fire_charge", "tnt", "tnt_minecart", "end_crystal",
            "respawn_anchor", "wither_skeleton_skull");
    /** 主线程专用：玩家打开容器时的快照（玩家 → 快照），关闭时取出对比。 */
    private static final Map<UUID, Snap> OPEN = new HashMap<>();
    /** 主线程专用：玩家最近一次右键的方块（给双箱这类拿不到坐标的容器兜底）。 */
    private static final Map<UUID, Click> LAST_CLICK = new HashMap<>();
    /** 方块类是否自己实现了右键逻辑，按类缓存（反射只做一次）。 */
    private static final Map<Class<?>, Boolean> INTERACTIVE = new HashMap<>();

    private record Snap(AbstractContainerMenu menu, String label, String where, Map<String, Integer> items) {}

    private record Click(long time, String label, String where) {}

    private TrackerHooks() {}

    /** 当前开服的追踪器（未开服或不是专用服务器时为 null）。 */
    static PlayerTracker tracker() {
        return tracker;
    }

    static void onStarting(ServerStartingEvent e) {
        if (e.getServer().isDedicatedServer()) tracker = new PlayerTracker(HISTORY_MINUTES);
    }

    static void onStopping(ServerStoppingEvent e) {
        tracker = null;
        OPEN.clear();
        LAST_CLICK.clear();
    }

    /**
     * 仅开发/端到端测试（-Daiadmin.devFakePlayer=true）：开发服务端没有真实玩家，用 NeoForge 的 FakePlayer "E2EBot"
     * 真的挖 6 个方块、打死 3 只僵尸、挖 1 块钻石矿、拉拉杆、右键猪、从箱子拿 5 颗钻石，走和真实玩家相同的事件路径
     * （BreakEvent、LivingDeathEvent、RightClickBlock、EntityInteract、PlayerContainerEvent.Close），用来验证事件采集；
     * 最后把它写成一份离线存档（playerdata + usercache），验证 get_offline_player。
     * FakePlayer 的 openMenu 是空实现，所以箱子界面由这里直接建好并发 Open 事件，关闭走真实的 closeContainer。
     */
    static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!Boolean.getBoolean("aiadmin.devFakePlayer") || tracker == null) return;
        MinecraftServer s = e.getServer();
        net.minecraft.server.level.ServerLevel lvl = s.overworld();
        BlockPos base = lvl.getSharedSpawnPos();
        ServerPlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(lvl,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("E2EBot".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "E2EBot"));
        fp.moveTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
        long now = System.currentTimeMillis();
        tracker.join("E2EBot", now);
        sample(tracker, fp, now);
        for (int i = 0; i < 6; i++) {
            BlockPos pos = base.offset(2 + i, 0, 2);
            lvl.setBlockAndUpdate(pos, (i % 3 == 0 ? net.minecraft.world.level.block.Blocks.IRON_ORE : net.minecraft.world.level.block.Blocks.STONE).defaultBlockState());
            fp.gameMode.destroyBlock(pos);
        }
        for (int i = 0; i < 3; i++) {
            net.minecraft.world.entity.monster.Zombie z = net.minecraft.world.entity.EntityType.ZOMBIE.create(lvl);
            if (z == null) continue;
            z.moveTo(base.getX() + 4, base.getY() + 1, base.getZ() - 3);
            lvl.addFreshEntity(z);
            z.hurt(lvl.damageSources().playerAttack(fp), 1000f);
        }
        try {
            BlockPos ore = base.offset(2, 0, 4);
            lvl.setBlockAndUpdate(ore, net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
            fp.gameMode.destroyBlock(ore);
            BlockPos lever = base.offset(-2, 0, 2);
            lvl.setBlockAndUpdate(lever.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            lvl.setBlockAndUpdate(lever, net.minecraft.world.level.block.Blocks.LEVER.defaultBlockState());
            fp.gameMode.useItemOn(fp, lvl, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(lever), net.minecraft.core.Direction.UP, lever, false));
            net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(lvl);
            if (pig != null) {
                pig.moveTo(base.getX() + 1, base.getY() + 1, base.getZ() + 1);
                lvl.addFreshEntity(pig);
                fp.interactOn(pig, InteractionHand.MAIN_HAND);
            }
            BlockPos chestPos = base.offset(-2, 0, -2);
            lvl.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            if (lvl.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chest.clearContent(); // 开发世界会保留上次运行的箱子，先清空
                chest.setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
                chest.setItem(1, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 3));
                AbstractContainerMenu menu = net.minecraft.world.inventory.ChestMenu.threeRows(1, fp.getInventory(), chest);
                fp.containerMenu = menu;
                NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fp, menu));
                fp.getInventory().add(chest.removeItemNoUpdate(0));
                chest.setItem(2, new ItemStack(net.minecraft.world.item.Items.DIRT, 7));
                fp.closeContainer();
            }
            sample(tracker, fp, System.currentTimeMillis());
            java.nio.file.Path dir = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.nbt.NbtIo.writeCompressed(fp.saveWithoutId(new net.minecraft.nbt.CompoundTag()), dir.resolve(fp.getUUID() + ".dat"));
            if (s.getProfileCache() != null) s.getProfileCache().add(fp.getGameProfile());
        } catch (Exception ex) {
            AiAdminMod.LOG.error("[AI管理] 开发模式：假玩家交互脚本失败", ex);
        }
        AiAdminMod.LOG.info("[AI管理] 开发模式：假玩家 E2EBot 已挖 6 个方块、打 3 只僵尸，并完成交互脚本");
    }

    static void onBreak(BlockEvent.BreakEvent e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getPlayer()) instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        BlockPos pos = e.getPos();
        t.count(McFmt.name(p), "break", McFmt.id(e.getState()), 1, McFmt.dimId(p), pos.getX(), pos.getY(), pos.getZ(), now);
        if (valuableOre(e.getState())) t.notable(McFmt.name(p), "ore", "挖到 " + McFmt.label(e.getState()), McFmt.where(p.level(), pos), now);
    }

    static void onPlace(BlockEvent.EntityPlaceEvent e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        long now = System.currentTimeMillis();
        BlockPos pos = e.getPos();
        t.count(McFmt.name(p), "place", McFmt.id(e.getPlacedBlock()), 1, McFmt.dimId(p), pos.getX(), pos.getY(), pos.getZ(), now);
        if (DANGER_ITEMS.contains(BuiltInRegistries.BLOCK.getKey(e.getPlacedBlock().getBlock()).getPath())) {
            t.notable(McFmt.name(p), "danger", "放置 " + McFmt.label(e.getPlacedBlock()), McFmt.where(p.level(), pos), now);
        }
    }

    /** 贵重矿石：钻石、绿宝石、远古残骸，以及模组的矿石（c:ores 标签里不是原版的）。挖到时带坐标记一笔，查矿透用。 */
    static boolean valuableOre(BlockState s) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        String path = id.getPath();
        if (path.contains("diamond_ore") || path.contains("emerald_ore") || path.equals("ancient_debris")) return true;
        return !id.getNamespace().equals("minecraft") && s.is(Tags.Blocks.ORES);
    }

    static void onDeath(LivingDeathEvent e) {
        PlayerTracker t = tracker;
        if (t == null) return;
        long now = System.currentTimeMillis();
        if (Players.real(e.getEntity()) instanceof ServerPlayer dead) {
            t.notable(McFmt.name(dead), "death", e.getSource().getLocalizedDeathMessage(dead).getString(), McFmt.where(dead), now);
        }
        if (Players.real(e.getSource().getEntity()) instanceof ServerPlayer killer && killer != e.getEntity()) {
            Entity victim = e.getEntity();
            String k = McFmt.name(killer);
            t.count(k, "kill", McFmt.id(victim), now);
            // 击杀玩家、命名生物、Boss 单独记一笔
            if (Players.real(victim) instanceof ServerPlayer vp) t.notable(k, "kill", "击杀了玩家 " + McFmt.name(vp), McFmt.where(killer), now);
            else if (victim.hasCustomName() || McFmt.id(victim).matches(".*(ender_dragon|wither|warden|elder_guardian)$")) {
                t.notable(k, "kill", "击杀了 " + McFmt.id(victim) + (victim.hasCustomName() ? "「" + victim.getName().getString() + "」" : ""),
                        McFmt.where(killer), now);
            }
        }
    }

    static void onDamage(LivingDamageEvent.Post e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p) || e.getNewDamage() <= 0) return;
        Entity src = e.getSource().getEntity();
        String by = src != null ? McFmt.id(src) : e.getSource().getMsgId();
        t.count(McFmt.name(p), "hurt", by, System.currentTimeMillis());
    }

    static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        PlayerTracker t = tracker;
        if (t != null && Players.real(e.getEntity()) instanceof ServerPlayer p && !e.getCrafting().isEmpty()) {
            t.count(McFmt.name(p), "craft", McFmt.id(e.getCrafting()), System.currentTimeMillis());
        }
    }

    static void onPickup(ItemEntityPickupEvent.Post e) {
        PlayerTracker t = tracker;
        if (t != null && Players.real(e.getPlayer()) instanceof ServerPlayer p) t.count(McFmt.name(p), "pickup", McFmt.id(e.getOriginalStack()), System.currentTimeMillis());
    }

    static void onUse(PlayerInteractEvent.RightClickBlock e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        long now = System.currentTimeMillis();
        BlockState state = p.level().getBlockState(e.getPos());
        ItemStack held = p.getMainHandItem();
        String at = McFmt.where(p.level(), e.getPos());
        if (!held.isEmpty() && DANGER_ITEMS.contains(BuiltInRegistries.ITEM.getKey(held.getItem()).getPath())) {
            t.notable(McFmt.name(p), "danger", "手持 " + McFmt.label(held) + " 右键 " + McFmt.label(state), at, now);
        }
        // 潜行 + 手里有东西时原版跳过方块的右键逻辑（放方块/用物品），不算交互
        if (p.isSecondaryUseActive() && !held.isEmpty()) return;
        boolean hasBe = p.level().getBlockEntity(e.getPos()) != null;
        if (!hasBe && !interactive(state.getBlock())) return; // 对着普通方块右键 = 放方块，place 事件会记
        String lbl = McFmt.label(state);
        t.count(McFmt.name(p), "use", McFmt.key(state), now);
        t.notable(McFmt.name(p), "use", "交互 " + lbl + (held.isEmpty() ? "" : "（手持 " + McFmt.label(held) + "）"), at, now);
        LAST_CLICK.put(p.getUUID(), new Click(now, lbl, at));
    }

    /**
     * 方块类（含父类，到 {@link BlockBehaviour} 为止）是否重写了右键方法 useWithoutItem / useItemOn。
     * 模组方块没有方块实体也可能能交互（如开关、机器外壳），靠这个判断。反射失败（类里引用了缺失的类）时当作能交互，宁可多记。
     */
    static boolean interactive(Block b) {
        return INTERACTIVE.computeIfAbsent(b.getClass(), c -> {
            try {
                for (Class<?> k = c; k != null && k != Block.class && k != BlockBehaviour.class; k = k.getSuperclass()) {
                    for (var m : k.getDeclaredMethods()) {
                        if (m.getName().equals("useWithoutItem") || m.getName().equals("useItemOn")) return true;
                    }
                }
                return false;
            } catch (Throwable ex) {
                return true;
            }
        });
    }

    static void onEntityUse(PlayerInteractEvent.EntityInteract e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        Entity target = e.getTarget();
        long now = System.currentTimeMillis();
        ItemStack held = p.getMainHandItem();
        t.count(McFmt.name(p), "interact_entity", McFmt.key(target), now);
        t.notable(McFmt.name(p), "interact_entity", "交互 " + McFmt.label(target) + (held.isEmpty() ? "" : "（手持 " + McFmt.label(held) + "）"),
                McFmt.where(p.level(), target.blockPosition()), now);
    }

    static void onItemUse(PlayerInteractEvent.RightClickItem e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p) || e.getItemStack().isEmpty()) return;
        long now = System.currentTimeMillis();
        ItemStack s = e.getItemStack();
        // 计数键和 drop/take/put 一样用物品本身的英文名（以前这里用自定义名，改过名的模组物品会单独成一个键）
        t.count(McFmt.name(p), "use_item", McFmt.itemKey(s), now);
        if (DANGER_ITEMS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) {
            t.notable(McFmt.name(p), "danger", "使用 " + McFmt.label(s), McFmt.where(p), now);
        }
    }

    static void onToss(ItemTossEvent e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getPlayer()) instanceof ServerPlayer p)) return;
        ItemStack s = e.getEntity().getItem();
        if (s.isEmpty()) return;
        long now = System.currentTimeMillis();
        t.count(McFmt.name(p), "drop", McFmt.itemKey(s), s.getCount(), null, 0, 0, 0, now);
        // 旁边有别的玩家时单独记：多半是给东西/交易
        List<String> near = new ArrayList<>();
        for (Player q : p.level().players()) if (q != p && q.distanceTo(p) <= 8) near.add(McFmt.name(q));
        if (!near.isEmpty()) {
            t.notable(McFmt.name(p), "drop", "在 " + String.join("、", near) + " 旁边丢出 " + McFmt.stack(s), McFmt.where(p), now);
        }
    }

    static void onTrade(TradeWithVillagerEvent e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        MerchantOffer o = e.getMerchantOffer();
        StringBuilder sb = new StringBuilder("和 ").append(McFmt.label(e.getAbstractVillager())).append(" 交易：付 ").append(McFmt.stack(o.getCostA()));
        if (!o.getCostB().isEmpty()) sb.append(" + ").append(McFmt.stack(o.getCostB()));
        sb.append(" → 得 ").append(McFmt.stack(o.getResult()));
        t.notable(McFmt.name(p), "trade", sb.toString(), McFmt.where(p.level(), e.getAbstractVillager().blockPosition()), System.currentTimeMillis());
    }

    // ———————————————————— 容器存取 ————————————————————

    static void onContainerOpen(PlayerContainerEvent.Open e) {
        if (tracker == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        AbstractContainerMenu menu = e.getContainer();
        Map<String, Integer> items = foreignItems(menu);
        if (items == null) return; // 没有非玩家背包的格子（例如只是玩家自己的背包界面）
        String label = null, at = null;
        for (Slot s : menu.slots) {
            Container c = s.container;
            if (c instanceof BlockEntity be) {
                label = McFmt.label(be.getBlockState());
                at = McFmt.where(p.level(), be.getBlockPos());
                break;
            }
            if (c instanceof Entity en && !(c instanceof Inventory)) {
                label = McFmt.label(en);
                at = McFmt.where(p.level(), en.blockPosition());
                break;
            }
        }
        if (label == null) {
            // 双箱（CompoundContainer）、模组的虚拟容器等拿不到坐标：用 3 秒内最近一次右键的方块兜底，再不行就用界面类型
            Click c = LAST_CLICK.get(p.getUUID());
            if (c != null && System.currentTimeMillis() - c.time() <= 3000) {
                label = c.label();
                at = c.where();
            } else {
                label = menuName(menu);
            }
        }
        OPEN.put(p.getUUID(), new Snap(menu, label, at, items));
    }

    /**
     * 关闭时和打开时的快照对比，差额就是这名玩家拿走/放入的东西。
     * 边界：打开期间漏斗、机器自己进出的物品也会算进去；Close 事件在 menu.removed() 之后触发，
     * 工作台这类临时格子此时已清空，和打开时（也是空的）相比没有差额，不会误报。
     */
    static void onContainerClose(PlayerContainerEvent.Close e) {
        PlayerTracker t = tracker;
        if (t == null || !(Players.real(e.getEntity()) instanceof ServerPlayer p)) return;
        String n = McFmt.name(p);
        Snap snap = OPEN.remove(p.getUUID());
        if (snap == null || snap.menu() != e.getContainer()) return;
        Map<String, Integer> after = foreignItems(e.getContainer());
        if (after == null) return;
        Map<String, Integer> diff = new HashMap<>(after);
        snap.items().forEach((k, v) -> diff.merge(k, -v, Integer::sum));
        List<String> took = new ArrayList<>(), put = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> d : diff.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList()) {
            if (d.getValue() < 0) {
                t.count(n, "take", d.getKey(), -d.getValue(), null, 0, 0, 0, now);
                if (took.size() < 12) took.add(d.getKey() + "×" + -d.getValue());
            } else if (d.getValue() > 0) {
                t.count(n, "put", d.getKey(), d.getValue(), null, 0, 0, 0, now);
            }
        }
        diff.entrySet().stream().filter(d -> d.getValue() > 0).sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12)
                .forEach(d -> put.add(d.getKey() + "×" + d.getValue()));
        if (took.isEmpty() && put.isEmpty()) return;
        StringBuilder sb = new StringBuilder(snap.label()).append("：");
        if (!took.isEmpty()) sb.append("拿走 ").append(String.join("、", took));
        if (!put.isEmpty()) sb.append(took.isEmpty() ? "" : "；").append("放入 ").append(String.join("、", put));
        t.notable(n, "container", sb.toString(), snap.where(), now);
    }

    /** 菜单里不属于玩家背包的格子的物品计数；一个都没有时返回 null。 */
    private static Map<String, Integer> foreignItems(AbstractContainerMenu menu) {
        Map<String, Integer> m = new HashMap<>();
        boolean any = false;
        for (Slot s : menu.slots) {
            if (s.container instanceof Inventory) continue;
            any = true;
            ItemStack st = s.getItem();
            if (!st.isEmpty()) m.merge(McFmt.itemKey(st), st.getCount(), Integer::sum);
        }
        return any ? m : null;
    }

    private static String menuName(AbstractContainerMenu menu) {
        try {
            ResourceLocation k = BuiltInRegistries.MENU.getKey(menu.getType());
            return k == null ? menu.getClass().getSimpleName() : "界面 " + k;
        } catch (UnsupportedOperationException ex) { // 玩家背包界面没有类型
            return menu.getClass().getSimpleName();
        }
    }

    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        PlayerTracker t = tracker;
        if (t != null && Players.real(e.getEntity()) instanceof ServerPlayer p) {
            // 显示维度名：原版只写路径；模组维度保留命名空间（以前一律只写路径，不同模组的同名维度分不清）
            t.notable(McFmt.name(p), "dimension", McFmt.dim(e.getFrom()) + " → " + McFmt.dim(e.getTo()), null, System.currentTimeMillis());
        }
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        PlayerTracker t = tracker;
        if (t != null && Players.real(e.getEntity()) instanceof ServerPlayer p) {
            t.join(McFmt.name(p), System.currentTimeMillis());
            sample(t, p, System.currentTimeMillis());
        }
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        OPEN.remove(e.getEntity().getUUID());
        LAST_CLICK.remove(e.getEntity().getUUID());
        PlayerTracker t = tracker;
        if (t != null && Players.real(e.getEntity()) instanceof ServerPlayer p) {
            sample(t, p, System.currentTimeMillis());
            t.leave(McFmt.name(p), System.currentTimeMillis());
        }
    }

    /** 每 {@link #SAMPLE_TICKS} tick（HookBus 调用）：给每名在线玩家采样一次位置等状态。 */
    static void onSampleTick(MinecraftServer s, long tick) {
        PlayerTracker t = tracker;
        if (t == null) return;
        long now = System.currentTimeMillis();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) sample(t, p, now);
    }

    /**
     * 每 {@link #INVENTORY_TICKS} tick（HookBus 调用，排在同一 tick 的采样之后）：记背包计数、清理过期记录。
     * 以前和采样在同一个监听里按玩家交替做（采样 A、背包 A、采样 B…），现在先全部采样再全部记背包，记下的数据一样。
     */
    static void onInventoryTick(MinecraftServer s, long tick) {
        PlayerTracker t = tracker;
        if (t == null) return;
        long now = System.currentTimeMillis();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) t.inventory(McFmt.name(p), inventoryCounts(p), now);
        t.purge(now);
    }

    static void sample(PlayerTracker t, ServerPlayer p, long now) {
        String biome = p.level().getBiome(p.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse(null);
        ItemStack held = p.getMainHandItem();
        t.sample(McFmt.name(p), new PlayerTracker.Sample(now, McFmt.dimId(p), p.getX(), p.getY(), p.getZ(), biome,
                p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(), held.isEmpty() ? null : McFmt.id(held),
                p.gameMode.getGameModeForPlayer().getName(), p.getVehicle() == null ? null : McFmt.id(p.getVehicle()), p.isSleeping()));
    }

    /** 背包（含盔甲、副手）里每种物品的总数。 */
    static Map<String, Integer> inventoryCounts(ServerPlayer p) {
        Map<String, Integer> m = new HashMap<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) m.merge(McFmt.id(s), s.getCount(), Integer::sum);
        }
        return m;
    }
}
