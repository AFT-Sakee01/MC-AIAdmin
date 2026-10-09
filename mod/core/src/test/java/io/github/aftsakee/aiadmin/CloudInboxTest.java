package io.github.aftsakee.aiadmin;

import io.github.aftsakee.aiadmin.agent.CloudInbox;
import io.github.aftsakee.aiadmin.agent.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CloudInboxTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final CloudInbox box = new CloudInbox(now::get);

    @Test
    void onlineByHeartbeatOrWaiter() throws Exception {
        assertFalse(box.online(120_000), "从没来过");
        box.heartbeat();
        assertTrue(box.online(120_000));
        now.addAndGet(121_000);
        assertFalse(box.online(120_000), "超时算掉线");
        // 正挂着 wait 的时候算在线
        CompletableFuture<List<CloudInbox.Item>> f = CompletableFuture.supplyAsync(() -> {
            try {
                return box.take(2000, 5, 2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (box.waiters() == 0 && System.currentTimeMillis() - t0 < 2000) Thread.sleep(5);
        now.addAndGet(500_000);
        assertTrue(box.online(120_000), "挂着等待 = 在线");
        assertNotNull(box.offer(Trigger.events(List.of("进服：Steve")), "说明", 10));
        List<CloudInbox.Item> got = f.get(2, TimeUnit.SECONDS);
        assertEquals(1, got.size(), "有新条目立即返回");
        assertEquals(CloudInbox.State.TAKEN, got.get(0).state());
    }

    @Test
    void handoffToLocalOnlyWhenNotTaken() throws Exception {
        CloudInbox.Item a = box.offer(Trigger.chat(List.of("Steve：@AI 你好"), ""), "说明", 10);
        CloudInbox.Item b = box.offer(Trigger.patrol(false), "说明", 10);
        List<CloudInbox.Item> got = box.take(0, 1, 2);
        assertEquals(List.of(a), got, "按顺序、max 生效");
        assertFalse(box.claimForLocal(a.id), "已被云端取走");
        assertTrue(box.claimForLocal(b.id), "没人取 → 本地接手");
        assertFalse(box.claimForLocal(b.id), "只接手一次");
        assertEquals(CloudInbox.State.FALLBACK, b.state());
        assertTrue(box.take(0, 5, 2).isEmpty(), "归本地的不会再给云端");
        assertNull(box.complete(b.id, "x"), "归本地的不能完成");
        assertNotNull(box.complete(a.id, "已回复"));
        assertEquals(CloudInbox.State.DONE, a.state());
        assertNull(box.complete(a.id, "again"));
    }

    @Test
    void waitTimesOutLimitsWaitersAndAutoCloses() throws Exception {
        long t0 = System.nanoTime();
        assertTrue(box.take(150, 5, 2).isEmpty());
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 140, "等够时间才返回");
        CompletableFuture<List<CloudInbox.Item>> w1 = CompletableFuture.supplyAsync(() -> {
            try {
                return box.take(1500, 5, 1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        long s = System.currentTimeMillis();
        while (box.waiters() == 0 && System.currentTimeMillis() - s < 2000) Thread.sleep(5);
        assertNull(box.take(1000, 5, 1), "挂起人数已满时拒绝");
        box.offer(Trigger.events(List.of("x")), "", 10);
        for (CloudInbox.Item x : w1.get(2, TimeUnit.SECONDS)) assertNotNull(box.complete(x.id, "done"));

        CloudInbox.Item it = box.offer(Trigger.events(List.of("死亡：Alex")), "", 10);
        box.take(0, 5, 2);
        now.addAndGet(14 * 60_000);
        assertTrue(box.expire(15 * 60_000).isEmpty());
        now.addAndGet(2 * 60_000);
        List<CloudInbox.Item> closed = box.expire(15 * 60_000);
        assertEquals(List.of(it), closed);
        assertEquals(CloudInbox.State.AUTO_CLOSED, it.state());
    }

    @Test
    void idleTakenItemsAreReclaimedForLocal() throws Exception {
        CloudInbox.Item a = box.offer(Trigger.chat(List.of("Steve：@AI 在吗"), ""), "", 10);
        box.take(0, 5, 2);
        now.addAndGet(100_000);
        assertTrue(box.reclaimIdle(120_000).isEmpty());
        now.addAndGet(30_000);
        assertEquals(List.of(a), box.reclaimIdle(120_000), "取走 2 分钟没任何动作：多半客户端已断开，收回给本地");
        assertEquals(CloudInbox.State.FALLBACK, a.state());
        assertNull(box.complete(a.id, "迟到的完成"), "已经归本地");
    }

    @Test
    void fullInboxRejects() {
        for (int i = 0; i < 3; i++) assertNotNull(box.offer(Trigger.events(List.of("e" + i)), "", 3));
        assertNull(box.offer(Trigger.events(List.of("e3")), "", 3), "满了交给本地");
        assertEquals(3, box.counts().get(CloudInbox.State.PENDING));
    }
}
