package io.github.aftsakee.aiadmin.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 事件里的"玩家"是不是真人。
 * <p>NeoForge 的 FakePlayer 是 ServerPlayer 的子类：Create 机械手、自动挖掘/放置机这类模组用它干活，
 * 只判断 instanceof ServerPlayer 的话，机器的放置/挖掘/拾取/击杀会被当成玩家行为——写进建筑账本、刷工程里程碑、
 * 触发"第一次造出/捡到"唤醒 AI。开发自测（e2e）用假玩家走真实路径，所以开了自测开关时放行。
 */
final class Players {
    static final boolean DEV_FAKE = Boolean.getBoolean("aiadmin.devFakePlayer") || Boolean.getBoolean("aiadmin.devSocialTest")
            || Boolean.getBoolean("aiadmin.devGoalTest") || Boolean.getBoolean("aiadmin.devTpaTest");

    private Players() {}

    /** 真实玩家（或自测时的假玩家）返回它本身，其他（非玩家、模组的假玩家、null）返回 null——可以直接接 instanceof 模式匹配。 */
    static ServerPlayer real(Object o) {
        return o instanceof ServerPlayer p && (!(p instanceof FakePlayer) || DEV_FAKE) ? p : null;
    }
}
