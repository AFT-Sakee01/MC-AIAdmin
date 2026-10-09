package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.tpa.TpaManager;
import io.github.aftsakee.aiadmin.tpa.TpaManager.Kind;
import io.github.aftsakee.aiadmin.tpa.TpaManager.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpaManagerTest {
    @TempDir
    Path tmp;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    @Test
    void sharedCooldownStartsWhenSent() {
        TpaManager m = new TpaManager(null);
        BotConfig.TpaCooldown cd = new BotConfig.TpaCooldown(); // 默认共享 300 秒
        assertEquals(0, m.cooldownLeft(a, Kind.TPA, cd, 1000));
        m.startCooldown(a, Kind.TPA, 1000);
        assertEquals(300_000, m.cooldownLeft(a, Kind.TPA, cd, 1000));
        assertEquals(200_000, m.cooldownLeft(a, Kind.TPAH, cd, 101_000)); // 共享：tpah 也在冷却
        assertEquals(0, m.cooldownLeft(a, Kind.TPAH, cd, 301_000));
        assertEquals(0, m.cooldownLeft(b, Kind.TPA, cd, 1000)); // 别人不受影响
    }

    @Test
    void independentCooldowns() {
        TpaManager m = new TpaManager(null);
        BotConfig.TpaCooldown cd = new BotConfig.TpaCooldown();
        cd.shared = false;
        cd.tpaSeconds = 60;
        cd.tpahSeconds = 600;
        m.startCooldown(a, Kind.TPA, 0);
        assertEquals(0, m.cooldownLeft(a, Kind.TPAH, cd, 1000)); // 独立：tpah 不受 tpa 影响
        assertEquals(59_000, m.cooldownLeft(a, Kind.TPA, cd, 1000));
        m.startCooldown(a, Kind.TPAH, 1000);
        assertEquals(600_000, m.cooldownLeft(a, Kind.TPAH, cd, 1000));
        cd.shared = true; // 运行中切回共享：取较晚的一次
        cd.seconds = 300;
        assertEquals(300_000, m.cooldownLeft(a, Kind.TPA, cd, 1000));
    }

    @Test
    void takeLatestOrNamedAndExpiry() {
        TpaManager m = new TpaManager(null);
        m.put(new Request(a, "Alice", c, "Carl", Kind.TPA, 1000));
        m.put(new Request(b, "Bob", c, "Carl", Kind.TPAH, 2000));
        assertEquals(2, m.pendingFor(c, 3000, 60).size());
        Request r = m.take(c, null, 3000, 60);
        assertEquals("Bob", r.fromName()); // 默认取最新
        assertEquals(Kind.TPAH, r.kind());
        assertNull(m.take(c, "bob", 3000, 60)); // 已取走
        m.put(new Request(b, "Bob", c, "Carl", Kind.TPA, 4000));
        assertEquals("Alice", m.take(c, "ALICE", 4000, 60).fromName()); // 按名字，不分大小写
        // 同一发起者的新请求替换旧的
        m.put(new Request(b, "Bob", c, "Carl", Kind.TPAH, 5000));
        assertEquals(1, m.pendingFor(c, 5000, 60).size());
        assertEquals(Kind.TPAH, m.pendingFor(c, 5000, 60).get(0).kind());
        // 过期：取不到，expire() 交出来
        assertNull(m.take(c, null, 5000 + 61_000, 60));
        m.put(new Request(a, "Alice", c, "Carl", Kind.TPA, 10_000));
        assertTrue(m.expire(20_000, 60).isEmpty());
        assertEquals("Alice", m.expire(71_000, 60).get(0).fromName());
        assertTrue(m.pendingFor(c, 71_000, 60).isEmpty());
    }

    @Test
    void dropPlayerRemovesBothDirections() {
        TpaManager m = new TpaManager(null);
        m.put(new Request(a, "Alice", c, "Carl", Kind.TPA, 0));
        m.put(new Request(c, "Carl", b, "Bob", Kind.TPA, 0));
        m.dropPlayer(c);
        assertTrue(m.pendingFor(c, 0, 60).isEmpty());
        assertTrue(m.pendingFor(b, 0, 60).isEmpty());
    }

    @Test
    void autoAcceptIsPerPlayerAndPersisted() {
        Path f = tmp.resolve("aiadmin/tpa-prefs.json");
        TpaManager m = new TpaManager(f);
        assertFalse(m.autoAccept(a)); // 默认手动
        m.setAutoAccept(a, true);
        assertTrue(m.autoAccept(a));
        assertFalse(m.autoAccept(b));
        TpaManager reloaded = new TpaManager(f);
        assertTrue(reloaded.autoAccept(a));
        reloaded.setAutoAccept(a, false);
        assertFalse(new TpaManager(f).autoAccept(a));
    }

    @Test
    void format() {
        assertEquals("5 分 0 秒", TpaManager.fmt(300_000));
        assertEquals("3 分 12 秒", TpaManager.fmt(191_500));
        assertEquals("9 秒", TpaManager.fmt(8_200));
    }
}
