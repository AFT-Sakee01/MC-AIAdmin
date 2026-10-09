package io.github.aftsakee.aiadmin.neoforge;

import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.config.Rules;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 代理线程 ↔ 服务端主线程的桥：所有读写游戏状态的操作都切到主线程执行（MC 的世界/玩家对象不是线程安全的），
 * 代理线程等待结果，最多 10 秒（服务端卡死时工具返回超时错误，而不是让代理线程永远挂着）。
 */
final class McBridge implements AgentService.Host, ActionTools.ServerActions {
    private static final long TIMEOUT_MS = 10_000;
    private final MinecraftServer server;

    McBridge(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 关服闸门：ServerStoppingEvent 一开始就关上（ServerHooks.onStopping 第一行）。之后不再往主线程投递——
     * tick 循环已经结束，投递的任务可能在存档阶段被执行；等服务端标记 stopped 后 submit/execute 甚至会直接在调用线程
     * （代理线程、远程线程）上同步执行，和存档并发读写世界，违反"游戏状态只在主线程读写"。
     */
    private volatile boolean closed;

    void close() {
        closed = true;
    }

    boolean closed() {
        return closed;
    }

    <T> T onServer(Supplier<T> s) {
        if (server.isSameThread()) return s.get();
        if (closed) throw new IllegalStateException("服务端正在关闭，不再执行");
        // 取消语义见 OnceTask：只有"还没开始"的任务能真的取消；已经在主线程执行的，如实告诉 AI 结果未知、别马上重试
        io.github.aftsakee.aiadmin.util.OnceTask<T> task = new io.github.aftsakee.aiadmin.util.OnceTask<>(s);
        java.util.concurrent.CompletableFuture<T> f = server.submit(task);
        try {
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            boolean cancelled = task.cancel();
            throw new IllegalStateException("服务端主线程 10 秒内没有响应（可能正在卡顿）"
                    + (cancelled ? "，这一步已取消、没有执行" : "，这一步已经开始执行、结果未知——先查看状态，不要立即重试"));
        } catch (InterruptedException e) {
            boolean cancelled = task.cancel();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(cancelled ? "已取消" : "已取消等待（这一步已经开始执行，结果未知）");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw new IllegalStateException(String.valueOf(c));
        }
    }

    // ———————— Host ————————

    @Override
    public int onlinePlayers() {
        return server.getPlayerCount();
    }

    @Override
    public String snapshotJson() {
        return onServer(() -> McTools.overview(server, true).toString());
    }

    @Override
    public String serverLabel() {
        String m = server.getMotd();
        return m == null || m.isBlank() ? "Minecraft 服务器" : m.replaceAll("§.", "");
    }

    @Override
    public void alertAdmins(String message, boolean inGame) {
        AiAdminMod.LOG.warn("[AI管理] {}", message);
        if (!inGame || closed) return;
        server.execute(() -> {
            int lvl = ServerHooks.rules().permissions.adminPermissionLevel;
            Component c = prefix(ServerHooks.config().botName, ChatFormatting.GOLD).append(Component.literal("⚠ " + message).withStyle(ChatFormatting.YELLOW));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (server.getProfilePermissions(p.getGameProfile()) >= lvl) p.sendSystemMessage(c);
            }
        });
    }

    // ———————— ServerActions ————————

    @Override
    public CommandOutcome runCommand(String command, RunContext ctx) {
        Rules.Permissions perm = ctx.rules.permissions;
        String name = ctx.cfg.botName;
        return onServer(() -> {
            StringBuilder out = new StringBuilder();
            boolean[] ok = {false};
            int[] res = {0};
            CommandSource sink = new CommandSource() {
                @Override
                public void sendSystemMessage(Component c) {
                    if (out.length() < 20000) out.append(c.getString()).append('\n');
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return perm.echoToOps;
                }
            };
            ServerLevel lvl = server.overworld();
            CommandSourceStack src = new CommandSourceStack(sink, Vec3.atBottomCenterOf(lvl.getSharedSpawnPos()), Vec2.ZERO, lvl,
                    perm.commandPermissionLevel, name, Component.literal(name), server, null)
                    .withCallback((success, result) -> {
                        ok[0] |= success;
                        res[0] += result;
                    });
            LogTap.botCommandStart(); // 执行期间主线程打出的 ERROR 不回灌成错误事件（见 LogTap）
            try {
                server.getCommands().performPrefixedCommand(src, command);
            } finally {
                LogTap.botCommandEnd();
            }
            return new CommandOutcome(ok[0], res[0], out.toString().trim());
        });
    }

    @Override
    public String sendChat(String message, String target, RunContext ctx) {
        String name = ctx.cfg.botName;
        return onServer(() -> {
            if (target == null) {
                server.getPlayerList().broadcastSystemMessage(prefix(name, ChatFormatting.AQUA).append(Component.literal(message)), false);
                return null;
            }
            ServerPlayer p = server.getPlayerList().getPlayerByName(target);
            if (p == null) return "玩家不在线：" + target;
            p.sendSystemMessage(prefix(name + " → 你", ChatFormatting.LIGHT_PURPLE).append(Component.literal(message)));
            AiAdminMod.LOG.info("[{} → {}] {}", name, target, message);
            return null;
        });
    }

    /** 不经过模型的系统提示（例如"别说了"的确认）：投递到主线程，不等待。 */
    @Override
    public void tellPlayer(String player, String message) {
        String name = ServerHooks.config().botName;
        if (closed) return;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayerByName(player);
            if (p != null) p.sendSystemMessage(prefix(name + " → 你", ChatFormatting.LIGHT_PURPLE).append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        });
    }

    static MutableComponent prefix(String name, ChatFormatting color) {
        return Component.empty().append(Component.literal("[" + name + "] ").withStyle(color));
    }
}
