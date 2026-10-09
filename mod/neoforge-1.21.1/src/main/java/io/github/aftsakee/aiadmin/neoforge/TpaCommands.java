package io.github.aftsakee.aiadmin.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.tpa.TpaManager;
import io.github.aftsakee.aiadmin.tpa.TpaManager.Kind;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

/**
 * 玩家传送请求：/tpa 玩家、/tpah 玩家、/tpa Y|N [发起者]、/tpa auto|manual。设置见 config.json 的 tpa 一节。
 * <ul>
 *   <li>拒绝和过期都<b>不</b>通知请求者（只告诉接收者），避免请求者从"有没有收到过期通知"反推出被拒绝。</li>
 *   <li>请求一发出就进入冷却；冷却共享或独立由 tpa.cooldown 决定。</li>
 *   <li>/tpa auto 只让 /tpa 自动接受；/tpah（把自己拉走）总是要手动确认。</li>
 *   <li>权限与开关在指令树（requires）和执行时各检查一次：指令树是玩家进服/reload 时下发的，设置可能已经变了。</li>
 * </ul>
 * 和机器人无关：这是玩家指令；后台/机器人以控制台身份执行时会因为"需要玩家"而失败。
 */
final class TpaCommands {
    private static volatile TpaManager mgr;
    private static int ticks;

    private TpaCommands() {}

    /** 仅开发自测用的监听（-Daiadmin.devTpaTest=true）；正式运行时什么都不做。 */
    static void init() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(TpaCommands::onStarted);
    }

    /**
     * 开发自测：开发服务端没有真实玩家，用 NeoForge FakePlayer 走一遍请求→接受/拒绝→传送的完整逻辑，
     * 检查真实坐标是否变化，结果写成 "TPA-DEV PASS/FAIL …" 日志行，供 scripts/e2e_tpa.py 断言。
     * FakePlayer 不在玩家表里，所以"是否在线"用这里的映射代替。
     */
    private static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!Boolean.getBoolean("aiadmin.devTpaTest") || mgr == null) return;
        MinecraftServer s = e.getServer();
        ServerLevel lvl = s.overworld();
        net.minecraft.core.BlockPos b0 = lvl.getSharedSpawnPos();
        java.util.Map<UUID, ServerPlayer> fakes = new java.util.HashMap<>();
        java.util.function.BiFunction<String, int[], ServerPlayer> mk = (n, xz) -> {
            ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(lvl,
                    new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(n.getBytes(java.nio.charset.StandardCharsets.UTF_8)), n));
            p.moveTo(b0.getX() + xz[0] + 0.5, b0.getY() + 1, b0.getZ() + xz[1] + 0.5);
            fakes.put(p.getUUID(), p);
            return p;
        };
        ServerPlayer a = mk.apply("TpA", new int[] {0, 0}), b = mk.apply("TpB", new int[] {40, 40}), c = mk.apply("TpC", new int[] {-40, -40}),
                d = mk.apply("TpD", new int[] {10, -30}), eP = mk.apply("TpE", new int[] {-20, 20}), f = mk.apply("TpF", new int[] {30, -10});
        Function<UUID, ServerPlayer> online = fakes::get;
        long now = System.currentTimeMillis();
        java.util.function.BiConsumer<String, Boolean> check = (what, ok) -> AiAdminMod.LOG.info("TPA-DEV {} {}", ok ? "PASS" : "FAIL", what);
        java.util.function.BiPredicate<ServerPlayer, ServerPlayer> together = (x, y) -> x.distanceTo(y) < 1.0;

        request(a, b, Kind.TPA, now, online);
        check.accept("tpa 发出后 B 有 1 条待处理请求、A 还没动", mgr.pendingFor(b.getUUID(), now, 60).size() == 1 && !together.test(a, b));
        answer(b, true, null, now, online);
        check.accept("B 接受后 A 被传送到 B 身边", together.test(a, b));
        request(a, c, Kind.TPA, now + 1000, online);
        check.accept("A 在冷却中，再发请求被拦下", mgr.pendingFor(c.getUUID(), now + 1000, 60).isEmpty());
        request(a, c, Kind.TPAH, now + 2000, online);
        check.accept("共享冷却：A 的 /tpah 也被拦下", mgr.pendingFor(c.getUUID(), now + 2000, 60).isEmpty());
        request(c, d, Kind.TPAH, now, online);
        answer(d, true, "TpC", now, online);
        check.accept("tpah：D 接受后 D 被传送到 C 身边", together.test(d, c));
        mgr.setAutoAccept(b.getUUID(), true);
        request(eP, b, Kind.TPA, now, online);
        check.accept("B 开了自动接受：E 的 /tpa 立即传送、没有待处理请求", together.test(eP, b) && mgr.pendingFor(b.getUUID(), now, 60).isEmpty());
        request(f, b, Kind.TPAH, now, online);
        check.accept("自动接受不包括 /tpah：F 的 /tpah 仍在等 B 确认", mgr.pendingFor(b.getUUID(), now, 60).size() == 1 && !together.test(f, b));
        double bx = b.getX();
        answer(b, false, null, now, online);
        check.accept("B 拒绝后谁都没动、请求清空", b.getX() == bx && !together.test(f, b) && mgr.pendingFor(b.getUUID(), now, 60).isEmpty());
        check.accept("不能向自己发请求", !request(d, d, Kind.TPA, now + 999_999, online));
        mgr.setAutoAccept(b.getUUID(), false);
        AiAdminMod.LOG.info("TPA-DEV DONE");
    }

    static void start(Path stateDir) {
        mgr = new TpaManager(stateDir.resolve("tpa-prefs.json"));
    }

    static void stop() {
        mgr = null;
    }

    static TpaManager manager() {
        return mgr;
    }

    private static BotConfig.Tpa cfg() {
        BotConfig c = ServerHooks.config();
        return c == null ? null : c.tpa;
    }

    private static boolean on() {
        BotConfig.Tpa t = cfg();
        return mgr != null && t != null && t.enabled;
    }

    private static boolean allowed(CommandSourceStack src, Kind kind) {
        BotConfig.Tpa t = cfg();
        if (!on()) return false;
        BotConfig.TpaCommand c = kind == Kind.TPA ? t.tpa : t.tpah;
        return c.enabled && src.hasPermission(c.permissionLevel);
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> tpa = Commands.literal("tpa").requires(src -> on());
        for (String y : new String[] {"Y", "y"}) {
            tpa.then(Commands.literal(y).executes(c -> answer(c, true, null))
                    .then(Commands.argument("from", StringArgumentType.word()).executes(c -> answer(c, true, StringArgumentType.getString(c, "from")))));
        }
        for (String n : new String[] {"N", "n"}) {
            tpa.then(Commands.literal(n).executes(c -> answer(c, false, null))
                    .then(Commands.argument("from", StringArgumentType.word()).executes(c -> answer(c, false, StringArgumentType.getString(c, "from")))));
        }
        tpa.then(Commands.literal("auto").executes(c -> setAuto(c, true)));
        tpa.then(Commands.literal("manual").executes(c -> setAuto(c, false)));
        tpa.then(Commands.argument("target", EntityArgument.player()).requires(src -> allowed(src, Kind.TPA))
                .executes(c -> request(c, Kind.TPA)));
        d.register(tpa);
        d.register(Commands.literal("tpah").requires(src -> allowed(src, Kind.TPAH))
                .then(Commands.argument("target", EntityArgument.player()).executes(c -> request(c, Kind.TPAH))));
    }

    // ———————————————————— 指令 ————————————————————

    private static int request(CommandContext<CommandSourceStack> c, Kind kind) throws CommandSyntaxException {
        ServerPlayer from = c.getSource().getPlayerOrException();
        ServerPlayer to = EntityArgument.getPlayer(c, "target");
        if (!allowed(c.getSource(), kind)) {
            say(from, Component.literal((kind == Kind.TPA ? "/tpa" : "/tpah") + " 已关闭或你没有权限").withStyle(ChatFormatting.RED));
            return 0;
        }
        return request(from, to, kind, System.currentTimeMillis(), id -> from.server.getPlayerList().getPlayer(id)) ? 1 : 0;
    }

    /** 发出请求（开发测试直接调用）。返回是否发出（含自动接受）。 */
    static boolean request(ServerPlayer from, ServerPlayer to, Kind kind, long now, Function<UUID, ServerPlayer> online) {
        TpaManager m = mgr;
        BotConfig.Tpa t = cfg();
        if (m == null || t == null) return false;
        if (from.getUUID().equals(to.getUUID())) {
            say(from, Component.literal("不能向自己发送传送请求").withStyle(ChatFormatting.RED));
            return false;
        }
        long left = m.cooldownLeft(from.getUUID(), kind, t.cooldown, now);
        if (left > 0) {
            say(from, Component.literal("传送冷却中，还要 " + TpaManager.fmt(left)).withStyle(ChatFormatting.RED));
            return false;
        }
        m.startCooldown(from.getUUID(), kind, now); // 请求发出即进入冷却
        String fn = name(from), tn = name(to);
        log(fn, (kind == Kind.TPA ? "/tpa " : "/tpah ") + tn);
        if (kind == Kind.TPA && m.autoAccept(to.getUUID())) {
            if (teleport(from, to, t, from, to)) {
                say(from, Component.literal(tn + " 开启了自动接受，已传送到 " + tn + " 身边"));
                say(to, Component.literal(fn + " 传送到了你身边（自动接受，/tpa manual 改为手动确认）").withStyle(ChatFormatting.GRAY));
            }
            return true;
        }
        m.put(new TpaManager.Request(from.getUUID(), fn, to.getUUID(), tn, kind, now));
        int sec = t.requestTimeoutSeconds;
        say(from, Component.literal("已向 " + tn + " 发送传送请求（" + sec + " 秒内有效）").withStyle(ChatFormatting.GRAY));
        MutableComponent msg = prefix().append(Component.literal(kind == Kind.TPA ? fn + " 想传送到你这里。" : fn + " 请求把你传送到 " + fn + " 身边。")
                        .withStyle(ChatFormatting.YELLOW))
                .append(button("[接受]", "/tpa Y " + fn, ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(button("[拒绝]", "/tpa N " + fn, ChatFormatting.RED))
                .append(Component.literal("（/tpa Y 接受、/tpa N 拒绝，" + sec + " 秒内有效）").withStyle(ChatFormatting.GRAY));
        to.sendSystemMessage(msg);
        return true;
    }

    private static int answer(CommandContext<CommandSourceStack> c, boolean accept, String fromName) throws CommandSyntaxException {
        ServerPlayer me = c.getSource().getPlayerOrException();
        return answer(me, accept, fromName, System.currentTimeMillis(), id -> me.server.getPlayerList().getPlayer(id)) ? 1 : 0;
    }

    /** 接受或拒绝（开发测试直接调用）。 */
    static boolean answer(ServerPlayer me, boolean accept, String fromName, long now, Function<UUID, ServerPlayer> online) {
        TpaManager m = mgr;
        BotConfig.Tpa t = cfg();
        if (m == null || t == null) return false;
        TpaManager.Request r = m.take(me.getUUID(), fromName, now, t.requestTimeoutSeconds);
        if (r == null) {
            say(me, Component.literal(fromName == null ? "没有待处理的传送请求" : "没有来自 " + fromName + " 的传送请求").withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (!accept) {
            say(me, Component.literal("已拒绝 " + r.fromName() + " 的传送请求").withStyle(ChatFormatting.GRAY)); // 不通知请求者
            log(name(me), "拒绝了 " + r.fromName() + " 的传送请求");
            return true;
        }
        ServerPlayer from = online.apply(r.from());
        if (from == null) {
            say(me, Component.literal(r.fromName() + " 已不在线").withStyle(ChatFormatting.GRAY));
            return false;
        }
        // TPA：请求者传送到我这里；TPAH：我传送到请求者那里
        ServerPlayer mover = r.kind() == Kind.TPA ? from : me;
        ServerPlayer dest = r.kind() == Kind.TPA ? me : from;
        if (teleport(mover, dest, t, from, me)) {
            say(me, Component.literal("已接受 " + r.fromName() + " 的传送请求").withStyle(ChatFormatting.GRAY));
            say(from, Component.literal(name(me) + " 接受了你的传送请求").withStyle(ChatFormatting.GREEN));
            log(name(me), "接受了 " + r.fromName() + " 的" + (r.kind() == Kind.TPA ? " /tpa" : " /tpah"));
        }
        return true;
    }

    private static int setAuto(CommandContext<CommandSourceStack> c, boolean auto) throws CommandSyntaxException {
        ServerPlayer me = c.getSource().getPlayerOrException();
        TpaManager m = mgr;
        if (m == null) return 0;
        m.setAutoAccept(me.getUUID(), auto);
        say(me, Component.literal(auto ? "已设为自动接受 /tpa（/tpah 仍需手动确认）" : "已设为手动确认传送请求"));
        return 1;
    }

    /** 每秒：清掉过期请求，只告诉接收者。 */
    static void tick(MinecraftServer server) {
        TpaManager m = mgr;
        BotConfig.Tpa t = cfg();
        if (m == null || t == null || ++ticks % 20 != 0) return;
        for (TpaManager.Request r : m.expire(System.currentTimeMillis(), t.requestTimeoutSeconds)) {
            ServerPlayer to = server.getPlayerList().getPlayer(r.to());
            if (to != null) say(to, Component.literal("来自 " + r.fromName() + " 的传送请求已过期").withStyle(ChatFormatting.GRAY));
        }
    }

    static void onLogout(UUID player) {
        TpaManager m = mgr;
        if (m != null) m.dropPlayer(player);
    }

    // ———————————————————— 工具 ————————————————————

    /** 传送 mover 到 dest 身边；跨维度被禁止时告诉双方。 */
    private static boolean teleport(ServerPlayer mover, ServerPlayer dest, BotConfig.Tpa t, ServerPlayer requester, ServerPlayer target) {
        if (!t.allowCrossDimension && mover.level() != dest.level()) {
            Component no = Component.literal("对方在别的维度，服务器不允许跨维度传送").withStyle(ChatFormatting.RED);
            say(requester, no);
            if (target != requester) say(target, no);
            return false;
        }
        mover.stopRiding();
        mover.teleportTo((ServerLevel) dest.level(), dest.getX(), dest.getY(), dest.getZ(), dest.getYRot(), dest.getXRot());
        // FakePlayer 的假网络连接会忽略 teleportTo 的位置同步（真实玩家不受影响）；开发自测用假玩家，所以直接设坐标
        if (mover instanceof net.neoforged.neoforge.common.util.FakePlayer && mover.level() == dest.level()) {
            mover.moveTo(dest.getX(), dest.getY(), dest.getZ(), dest.getYRot(), dest.getXRot());
        }
        return true;
    }

    private static MutableComponent button(String text, String command, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static MutableComponent prefix() {
        return Component.empty().append(Component.literal("[传送] ").withStyle(ChatFormatting.AQUA));
    }

    private static void say(ServerPlayer p, Component c) {
        p.sendSystemMessage(prefix().append(c));
    }

    private static void log(String who, String text) {
        ServerHooks.activity().add("tpa", who, text); // 机器人能在 get_recent_activity 里看到
    }

    static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }
}
