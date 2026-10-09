package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.Rules;

import java.time.LocalDateTime;

/** 测试用：访问 agent 包内的方法（启停时段判断在 ScheduleGate）。 */
public final class AgentServiceAccess {
    private AgentServiceAccess() {}

    public static String timeBlock(Rules.Schedule s, LocalDateTime t) {
        return ScheduleGate.timeBlock(s, t);
    }
}
