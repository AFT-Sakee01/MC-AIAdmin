package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.agent.Quota;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaTest {
    @TempDir
    Path dir;

    private static Rules.QuotaRules rules(String period, int runs, long tokens, double cost) {
        Rules.QuotaRules q = new Rules.QuotaRules();
        Rules.Window w = new Rules.Window();
        w.period = period;
        w.maxRuns = runs;
        w.maxTokens = tokens;
        w.maxCost = cost;
        q.windows = List.of(w);
        return q;
    }

    @Test
    void runsLimitPerDayResetsNextDay() {
        Rules.QuotaRules q = rules("day", 3, 0, 0);
        Quota quota = new Quota(dir.resolve("q.json"));
        LocalDateTime d1 = LocalDateTime.of(2026, 10, 7, 10, 0);
        for (int i = 0; i < 3; i++) {
            assertNull(quota.blocked(q, false, d1));
            quota.recordStart(q, false, d1);
        }
        String why = quota.blocked(q, false, d1);
        assertNotNull(why);
        assertTrue(why.contains("今天") && why.contains("10-08 00:00"), why);
        // 管理员默认不受限，但照样计数
        assertNull(quota.blocked(q, true, d1));
        q.blockAdminWhenExhausted = true;
        assertNotNull(quota.blocked(q, true, d1));
        // 第二天恢复
        assertNull(quota.blocked(q, false, d1.plusDays(1)));
    }

    @Test
    void costAndTokenLimitsAndAlertsOnce() {
        Rules.QuotaRules q = rules("month", 0, 1000, 1.0);
        q.warnAtPercent = 20;
        Quota quota = new Quota(dir.resolve("q.json"));
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
        quota.recordStart(q, false, t);
        quota.recordUsage(q, false, 850, 0.5, t);
        List<String> a = quota.newAlerts(q, t);
        assertEquals(1, a.size(), a.toString()); // token 剩 15% < 20%，花费剩 50%
        assertTrue(a.get(0).contains("token"));
        assertTrue(quota.newAlerts(q, t).isEmpty()); // 同一时段不重复
        quota.recordUsage(q, false, 10, 0.6, t);
        a = quota.newAlerts(q, t);
        assertEquals(1, a.size(), a.toString());
        assertTrue(a.get(0).contains("花费") && a.get(0).contains("用完"));
        assertNotNull(quota.blocked(q, false, t));
        assertTrue(quota.report(q, t).contains("本月"));
    }

    @Test
    void persistsAcrossRestartAndUncountedAdmin() {
        Rules.QuotaRules q = rules("hour", 2, 0, 0);
        q.countAdminRuns = false;
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 30);
        Quota a = new Quota(dir.resolve("q.json"));
        a.recordStart(q, false, t);
        a.recordStart(q, true, t); // 不计
        Quota b = new Quota(dir.resolve("q.json"));
        assertNull(b.blocked(q, false, t));
        b.recordStart(q, false, t);
        assertNotNull(b.blocked(q, false, t));
        assertNull(b.blocked(q, false, t.plusHours(1)));
    }
}
