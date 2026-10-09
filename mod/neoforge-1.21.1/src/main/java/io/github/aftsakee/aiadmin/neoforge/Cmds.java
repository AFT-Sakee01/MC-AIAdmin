package io.github.aftsakee.aiadmin.neoforge;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 几个指令类共用的小工具（以前 GoalHooks、SocialHooks、StoryHooks 各抄一份）。 */
final class Cmds {
    /** "OP"的权限等级：原版 OP 默认 4，能用 /gamemode 等管理指令的最低是 2——聊天授权、/ai 私聊的 OP 判断都按 2。 */
    static final int OP_LEVEL = 2;

    private Cmds() {}

    /** 指令必须由玩家执行：不是玩家时回一句并返回 null。 */
    static ServerPlayer player(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) c.getSource().sendFailure(Component.literal("这个指令要由玩家在游戏里使用"));
        return p;
    }

    static boolean isOp(MinecraftServer server, ServerPlayer p) {
        return p != null && server.getProfilePermissions(p.getGameProfile()) >= OP_LEVEL;
    }
}
