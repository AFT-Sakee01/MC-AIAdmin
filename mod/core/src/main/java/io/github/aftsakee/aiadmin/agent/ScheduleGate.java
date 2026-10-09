package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.util.Text;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

/**
 * 启停限制（从 AgentService 拆出，1.0.0.34）：开服延迟、运行时段与星期、最少在线人数、连续失败后暂停。
 * <ul>
 *   <li>锁：本对象自己的锁，只保护开服时间和连续失败计数。持锁时只读在线人数（读服务端玩家表的大小，不切主线程、不拿别的锁），
 *       从不回调 AgentService，所以 AgentService / 运行线程持着服务锁调用这里（锁顺序 服务 → 启停）不会死锁。</li>
 *   <li>时间：取自注入的时钟（测试可以推进）；运行时段按时钟的时区算。</li>
 * </ul>
 */
final class ScheduleGate {
    private final Clock clock;
    private final IntSupplier online;
    private long startedAt;
    private long failurePausedUntil;
    private int consecutiveFailures;

    ScheduleGate(Clock clock, IntSupplier online) {
        this.clock = clock;
        this.online = online;
    }

    /** 服务启动：开服延迟（schedule.startup_delay_seconds）从这一刻算。 */
    synchronized void started() {
        startedAt = clock.millis();
    }

    /** 不允许运行的原因，允许则 null。管理员提问在 admin_bypass 时不受限。 */
    synchronized String block(Rules r, boolean admin) {
        Rules.Schedule s = r.schedule;
        if (admin && s.adminBypass) return null;
        long now = clock.millis();
        if (failurePausedUntil > now) return "连续失败后暂停中，" + Text.time(failurePausedUntil).substring(11, 16) + " 恢复（/aibot resume 可提前恢复）";
        if (startedAt > 0 && now - startedAt < s.startupDelaySeconds * 1000L) return "开服 " + s.startupDelaySeconds + " 秒内不自动运行";
        String why = timeBlock(s, LocalDateTime.now(clock));
        if (why != null) return why;
        if (s.minOnlinePlayers > 0 && online.getAsInt() < s.minOnlinePlayers) return "在线人数少于 " + s.minOnlinePlayers;
        return null;
    }

    /**
     * 记一次运行结果。失败累计到 schedule.pause_after_consecutive_failures 次就暂停自动运行 failure_pause_minutes 分钟并清零计数，
     * 返回要发给管理员的提醒；其余情况返回 null（成功会清零计数）。
     */
    synchronized String recordRun(boolean ok, Rules.Schedule s, String lastError) {
        if (ok) {
            consecutiveFailures = 0;
            return null;
        }
        consecutiveFailures++;
        int lim = s.pauseAfterConsecutiveFailures;
        if (lim <= 0 || consecutiveFailures < lim) return null;
        failurePausedUntil = clock.millis() + s.failurePauseMinutes * 60_000L;
        consecutiveFailures = 0;
        return "连续失败 " + lim + " 次（最后一次：" + Text.brief(lastError, 80) + "），自动运行暂停 " + s.failurePauseMinutes + " 分钟";
    }

    /** 解除"连续失败暂停"，返回之前是否在暂停中。 */
    synchronized boolean resume() {
        boolean was = failurePausedUntil > clock.millis();
        failurePausedUntil = 0;
        consecutiveFailures = 0;
        return was;
    }

    /**
     * 时段 + 星期。跨午夜的时段（如 20:00-02:00）在午夜之后的部分算作"开始那天"的时段：
     * 只允许周五时，周六 01:00 仍属于周五晚上的时段。
     */
    static String timeBlock(Rules.Schedule s, LocalDateTime t) {
        int m = t.getHour() * 60 + t.getMinute();
        int today = t.getDayOfWeek().getValue();
        if (s.activeHours.isEmpty()) {
            return s.activeWeekdays.isEmpty() || s.activeWeekdays.contains(today) ? null : "今天不在运行日";
        }
        boolean inAny = false;
        for (String h : s.activeHours) {
            String r = h.trim();
            if (!inHours(r, m)) continue;
            inAny = true;
            int a = Integer.parseInt(r.substring(0, 2)) * 60 + Integer.parseInt(r.substring(3, 5));
            int day = a > m ? (today == 1 ? 7 : today - 1) : today; // 已过午夜的那部分属于前一天的时段
            if (s.activeWeekdays.isEmpty() || s.activeWeekdays.contains(day)) return null;
        }
        return inAny ? "今天不在运行日" : "不在运行时段 " + String.join("、", s.activeHours);
    }

    /** "HH:mm-HH:mm"，结束早于开始表示跨午夜；开始等于结束表示全天。 */
    static boolean inHours(String range, int minuteOfDay) {
        int a = Integer.parseInt(range.substring(0, 2)) * 60 + Integer.parseInt(range.substring(3, 5));
        int b = Integer.parseInt(range.substring(6, 8)) * 60 + Integer.parseInt(range.substring(9, 11));
        if (a == b) return true;
        return a < b ? minuteOfDay >= a && minuteOfDay < b : minuteOfDay >= a || minuteOfDay < b;
    }
}
