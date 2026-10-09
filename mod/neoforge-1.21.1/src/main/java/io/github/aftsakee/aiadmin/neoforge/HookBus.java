package io.github.aftsakee.aiadmin.neoforge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 事件分发器：本模组在 NeoForge 游戏总线上的全部订阅都在 {@link #register} 这一张表里，按固定顺序分发给各 Hooks 的处理方法。
 * <p>原因：以前 ServerHooks、TrackerHooks、StoryHooks、GoalHooks、SocialHooks 各自 addListener，方块放置、伤害、拾取、合成订两次，
 * LivingDeathEvent 订三次，4 个 tick 监听各自计数；处理顺序靠 AiAdminMod 里 init() 的先后隐式保证。
 * <ul>
 *   <li><b>一组一订</b>：按（事件类型, 优先级, 收不收已取消的）分组，每组只在总线上订一次（{@link Chain#subscribe} 查重，重复注册直接报错）。
 *       优先级/是否收已取消不同的不能合并——例如 LivingDeathEvent 用 LOWEST、不收已取消（看别的模组取消死亡之后的最终结果），
 *       LivingDropsEvent 用 LOWEST、收已取消（墓碑模组取消掉落也要记成 0 件），各自一组，语义和原来各自订阅时一样。</li>
 *   <li><b>顺序</b>：组内按 then(...) 的先后调用，等于原来同优先级下按注册先后（ServerHooks → TrackerHooks → GoalHooks → SocialHooks → StoryHooks）。
 *       不收已取消的组：每个处理者调用前再看一次是否已取消（和总线对单个监听的检查一样；本模组的处理者都不取消事件）。</li>
 *   <li><b>隔离</b>：逐个 try/catch。总线自己不隔离——一个监听抛异常，同一事件后面的监听（包括别的模组的）都收不到，异常还会抛回游戏
 *       （tick 里抛就是崩服）。这里一个处理者出错只记日志、跳过它，后面的照常收到，异常不再抛回游戏。</li>
 *   <li><b>日志限频</b>：同一处理者 + 异常类型 10 分钟内只写第一条（带堆栈），之后计数，过了窗口再出现时连同"期间又出现 N 次"写一条。
 *       写 MC 日志（AiAdminMod.LOG），不写 JsonlLog：JsonlLog 同步写文件，而这里在主线程上；而且它随机器人开服才建、关服就关。</li>
 *   <li><b>tick</b>：只订一次 ServerTickEvent.Post，一个计数器（第一个 tick 记 1）；各处理者按自己的周期（每 N tick）被调用，
 *       计数到 N 的倍数时调用，相位和原来各自 ++ticks % N 的写法一致（原来的计数器只在各自的状态非空时才加，但专用服务器上这些状态
 *       在第一个 tick 之前就都建好了，所以相位不变；非专用服务器上处理者自己发现状态为空直接返回）。</li>
 * </ul>
 * 约束：游戏事件都在服务端主线程投递（RegisterCommandsEvent 可能在资源加载线程上，那几个处理者只建指令树、不碰游戏状态），
 * 处理者只做内存操作（主线程不做文件和网络 IO；已知例外写在各 Hooks 的类注释里）。
 * 分发器本身可重入（开发自测在 ServerStartedEvent 里又投递容器、死亡等事件），除日志限频表外没有共享状态。
 * TpaCommands 的开发自测监听（ServerStartedEvent）仍由它自己订阅，排在本表之后；它的过期检查每 tick 从这里调用、自己每 20 tick 处理一次。
 */
final class HookBus {
    static final long ERROR_WINDOW_MS = 10 * 60_000L;
    /** "处理者|异常类" → {上次写日志的时间, 之后被压下的次数}。处理者是固定的几十个，异常类有限，不会无限增长。 */
    private static final Map<String, long[]> ERRORS = new ConcurrentHashMap<>();
    private static final Set<String> SUBSCRIBED = new HashSet<>();
    private static final List<Periodic> TICKS = new ArrayList<>();
    /** 主线程专用：服务端 tick 计数（第一个 tick 为 1）。 */
    private static long tick;
    private static boolean registered;

    private HookBus() {}

    /** tick 处理者：server 是当前服务端，tick 是分发器的计数（周期的倍数）。 */
    @FunctionalInterface
    interface TickHandler {
        void tick(MinecraftServer server, long tick);
    }

    private record Handler<E>(String name, Consumer<E> fn) {}

    private record Periodic(String name, int period, TickHandler fn) {}

    /** 只在模组构造时调用一次（AiAdminMod）。 */
    static synchronized void register(IEventBus bus) {
        if (registered) throw new IllegalStateException("HookBus 只能注册一次");
        registered = true;

        // ———————————————————— 生命周期（顺序有依赖） ————————————————————
        on(ServerStartingEvent.class)
                .then("server.starting", ServerHooks::onStarting)   // 读设置、建 AgentService、工具、远程接口（其他 Hooks 都要用）
                .then("tracker.starting", TrackerHooks::onStarting) // 建 PlayerTracker
                .subscribe(bus);
        on(ServerStartedEvent.class)
                .then("server.started", ServerHooks::onStarted)         // 开发：定时关服、控制台指令
                .then("tracker.dev_fake_player", TrackerHooks::onStarted) // 开发：假玩家 E2EBot
                .then("goal.started", GoalHooks::onStarted)             // 建目标引擎和游戏资料快照
                .then("story.dev_social", StoryHooks::onStarted)        // 开发：假玩家 E2ESocial；要在 goal.started 之后（/goal 节点的 requires 看 GoalHooks 的引擎）
                .subscribe(bus);
        on(ServerStoppingEvent.class)
                .then("server.stopping", ServerHooks::onStopping) // 先关闸门、停机器人并落盘
                .then("tracker.stopping", TrackerHooks::onStopping)
                .then("goal.stopping", GoalHooks::onStopping)     // 存目标进度
                .then("story.stopping", StoryHooks::onStopping)   // 兜底落盘（通常机器人已停、什么都不做）
                .subscribe(bus);
        on(RegisterCommandsEvent.class)
                .then("server.commands", ServerHooks::onRegisterCommands) // /aibot、/tpa、/tpah
                .then("goal.commands", GoalHooks::onRegisterCommands)     // /goal（必须先于 story：同名字面节点合并时 requires 用先注册的）
                .then("social.commands", SocialHooks::onRegisterCommands) // /ai
                .then("story.commands", StoryHooks::onRegisterCommands)   // /goal project
                .subscribe(bus);

        // ———————————————————— 进出服、聊天、指令、进度 ————————————————————
        on(PlayerEvent.PlayerLoggedInEvent.class)
                .then("server.login", ServerHooks::onLogin)
                .then("tracker.login", TrackerHooks::onLogin)
                .then("goal.login", GoalHooks::onLogin)
                .subscribe(bus);
        on(PlayerEvent.PlayerLoggedOutEvent.class)
                .then("server.logout", ServerHooks::onLogout)
                .then("tracker.logout", TrackerHooks::onLogout)
                .then("goal.logout", GoalHooks::onLogout)
                .subscribe(bus);
        on(PlayerEvent.PlayerChangedDimensionEvent.class).then("tracker.dimension", TrackerHooks::onDimension).subscribe(bus);
        on(AdvancementEvent.AdvancementEarnEvent.class).then("server.advancement", ServerHooks::onAdvancement).subscribe(bus);
        on(ServerChatEvent.class).then("server.chat", ServerHooks::onChat).subscribe(bus);       // 被聊天过滤模组取消的不收
        on(CommandEvent.class).then("server.command", ServerHooks::onCommand).subscribe(bus);    // 被取消的指令不收

        // ———————————————————— 战斗 ————————————————————
        // LOWEST、不收已取消：复活类模组会在更低优先级取消死亡，要看最终结果（ServerHooks 1.0.0.31 起；另两个一直是 LOWEST）
        on(LivingDeathEvent.class, EventPriority.LOWEST, false)
                .then("server.death", ServerHooks::onDeath)          // 玩家死亡 → 动态 + 前因后果 + 死亡事件
                .then("tracker.death", TrackerHooks::onDeath)        // 死亡/击杀计数和记录
                .then("story.entity_death", StoryHooks::onEntityDeath) // 非玩家：击败 Boss、救下队友
                .subscribe(bus);
        // LOWEST、收已取消：墓碑类模组取消掉落（物品进了墓碑）也要记成 0 件，否则 5 分钟后误报"没回去捡"
        on(LivingDropsEvent.class, EventPriority.LOWEST, true).then("story.drops", StoryHooks::onDrops).subscribe(bus);
        on(LivingDamageEvent.Post.class)
                .then("tracker.hurt", TrackerHooks::onDamage)
                .then("story.hurt", StoryHooks::onDamage)
                .subscribe(bus);
        on(AnimalTameEvent.class, EventPriority.LOWEST, false).then("story.tame", StoryHooks::onTame).subscribe(bus);

        // ———————————————————— 方块、物品、交互（LOWEST、不收已取消的：被领地/保护模组拦下的不算） ————————————————————
        on(BlockEvent.BreakEvent.class, EventPriority.LOWEST, false).then("tracker.break", TrackerHooks::onBreak).subscribe(bus);
        on(BlockEvent.EntityPlaceEvent.class, EventPriority.LOWEST, false)
                .then("tracker.place", TrackerHooks::onPlace)
                .then("story.place", StoryHooks::onPlace) // 建筑账本、工程里程碑
                .subscribe(bus);
        on(PlayerEvent.ItemCraftedEvent.class)
                .then("tracker.craft", TrackerHooks::onCraft)
                .then("story.craft", StoryHooks::onCraft) // 第一次造出
                .subscribe(bus);
        on(ItemEntityPickupEvent.Post.class)
                .then("tracker.pickup", TrackerHooks::onPickup)
                .then("story.pickup", StoryHooks::onPickup) // 回死亡点捡东西、第一次捡到
                .subscribe(bus);
        on(PlayerInteractEvent.RightClickBlock.class, EventPriority.LOWEST, false).then("tracker.use_block", TrackerHooks::onUse).subscribe(bus);
        on(PlayerInteractEvent.EntityInteract.class, EventPriority.LOWEST, false).then("tracker.use_entity", TrackerHooks::onEntityUse).subscribe(bus);
        on(PlayerInteractEvent.RightClickItem.class, EventPriority.LOWEST, false).then("tracker.use_item", TrackerHooks::onItemUse).subscribe(bus);
        on(ItemTossEvent.class, EventPriority.LOWEST, false).then("tracker.toss", TrackerHooks::onToss).subscribe(bus);
        on(TradeWithVillagerEvent.class).then("tracker.trade", TrackerHooks::onTrade).subscribe(bus);
        on(PlayerContainerEvent.Open.class).then("tracker.container_open", TrackerHooks::onContainerOpen).subscribe(bus);
        on(PlayerContainerEvent.Close.class).then("tracker.container_close", TrackerHooks::onContainerClose).subscribe(bus);

        // ———————————————————— tick（一个计数器，各自的周期；同一 tick 内按这里的顺序） ————————————————————
        every(1, "tpa.expire", (s, t) -> TpaCommands.tick(s));             // 传送请求过期（TpaCommands 自己每 20 tick 处理一次）
        every(20, "server.tick", ServerHooks::onTick);                      // 每秒：TPS 监测、日志错误、开发聊天文件
        every(TrackerHooks.SAMPLE_TICKS, "tracker.sample", TrackerHooks::onSampleTick);       // 每 10 秒：位置等状态采样
        every(TrackerHooks.INVENTORY_TICKS, "tracker.inventory", TrackerHooks::onInventoryTick); // 每分钟：背包计数、清理过期记录
        every(20, "goal.tick", GoalHooks::onTick);                          // 每秒看一次，按 check_interval_seconds 检查目标
        every(200, "story.tick", StoryHooks::onTick);                       // 每 10 秒：死后没回去捡的跟进
        on(ServerTickEvent.Post.class).then("hookbus.tick", HookBus::onTick).subscribe(bus);
    }

    // ———————————————————— 分组 ————————————————————

    private static <E extends Event> Chain<E> on(Class<E> type) {
        return on(type, EventPriority.NORMAL, false); // 和 addListener(Consumer) 的默认值一样
    }

    private static <E extends Event> Chain<E> on(Class<E> type, EventPriority priority, boolean receiveCanceled) {
        return new Chain<>(type, priority, receiveCanceled);
    }

    /** 一组（事件类型, 优先级, 收不收已取消的）的处理者，在总线上只订一次。 */
    private static final class Chain<E extends Event> implements Consumer<E> {
        private final Class<E> type;
        private final EventPriority priority;
        private final boolean receiveCanceled;
        private final List<Handler<E>> handlers = new ArrayList<>();

        Chain(Class<E> type, EventPriority priority, boolean receiveCanceled) {
            this.type = type;
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
        }

        Chain<E> then(String name, Consumer<E> fn) {
            handlers.add(new Handler<>(name, fn));
            return this;
        }

        void subscribe(IEventBus bus) {
            String key = type.getName() + "|" + priority + "|" + receiveCanceled;
            if (!SUBSCRIBED.add(key)) throw new IllegalStateException("同一组事件订阅了两次：" + key + "（加到已有的那组里）");
            bus.addListener(priority, receiveCanceled, type, this);
        }

        @Override
        public void accept(E e) {
            for (Handler<E> h : handlers) {
                // 和总线对单个"不收已取消"监听的检查一样：前面有人取消了，后面的就不收（本模组的处理者都不取消事件）
                if (!receiveCanceled && e instanceof ICancellableEvent c && c.isCanceled()) return;
                try {
                    h.fn().accept(e);
                } catch (RuntimeException | LinkageError ex) {
                    failed(h.name(), type.getSimpleName(), ex);
                }
            }
        }
    }

    // ———————————————————— tick ————————————————————

    private static void every(int period, String name, TickHandler fn) {
        if (period <= 0) throw new IllegalArgumentException("tick 周期必须大于 0：" + name);
        TICKS.add(new Periodic(name, period, fn));
    }

    private static void onTick(ServerTickEvent.Post e) {
        long t = ++tick;
        MinecraftServer server = e.getServer();
        for (Periodic p : TICKS) {
            if (t % p.period() != 0) continue;
            try {
                p.fn().tick(server, t);
            } catch (RuntimeException | LinkageError ex) {
                failed(p.name(), "ServerTickEvent.Post", ex);
            }
        }
    }

    // ———————————————————— 出错 ————————————————————

    /** 记一个处理者的异常：同一处理者 + 异常类型 10 分钟内只写第一条（带堆栈），之后计数，窗口过后再出现时连同次数写一条。 */
    private static void failed(String handler, String event, Throwable ex) {
        long now = System.currentTimeMillis();
        long[] st = ERRORS.computeIfAbsent(handler + "|" + ex.getClass().getName(), k -> new long[2]);
        long suppressed;
        synchronized (st) {
            if (st[0] != 0 && now - st[0] < ERROR_WINDOW_MS) {
                st[1]++;
                return;
            }
            suppressed = st[1];
            st[0] = now;
            st[1] = 0;
        }
        AiAdminMod.LOG.error("[AI管理] 事件处理出错（{}，{}），已跳过这一个处理者，其他照常{}", handler, event,
                suppressed > 0 ? "；上一条之后同样的错误又出现 " + suppressed + " 次（10 分钟内只记一条）" : "", ex);
    }
}
