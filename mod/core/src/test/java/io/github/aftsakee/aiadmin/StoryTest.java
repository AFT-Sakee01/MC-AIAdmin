package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.BuildLedger;
import io.github.aftsakee.aiadmin.agent.CombatLog;
import io.github.aftsakee.aiadmin.agent.Milestones;
import io.github.aftsakee.aiadmin.agent.ProjectStore;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 战斗前因后果、"第一次"、工程、建筑账本，以及它们进提示词和事件的方式。 */
class StoryTest {
    @TempDir
    Path tmp;

    private static CombatLog.Hit hit(long t, float dmg, String attacker, String name, String uuid, float hp) {
        return new CombatLog.Hit(t, dmg, "mob", attacker, name, uuid, false, null, hp, 20);
    }

    @Test
    void deathStoryRetrievalAndRescue() {
        CombatLog c = new CombatLog();
        long t = 1_000_000L;
        c.hurt("Steve", hit(t - 50_000, 3, "minecraft:zombie", null, "z0", 17)); // 50 秒前：不算进死前 30 秒
        c.hurt("Steve", hit(t - 20_000, 4, "minecraft:zombie", null, "z1", 13));
        c.hurt("Steve", hit(t - 10_000, 5, "minecraft:zombie", null, "z1", 8));
        c.hurt("Steve", new CombatLog.Hit(t - 2_000, 8, "arrow", "minecraft:skeleton", "Bob", "s1", false, "弓 [minecraft:bow]", 0, 20));
        c.hurt("Alex", hit(t - 5_000, 6, "minecraft:zombie", null, "z2", 9));
        assertTrue(c.hurtRecently("Alex", t));
        CombatLog.Death d = c.death("Steve", "Steve 被骷髅射杀", "minecraft:overworld", 10, 64, -20,
                List.of(new CombatLog.Nearby("Alex", 6, 9, true), new CombatLog.Nearby("Carl", 25, 20, false)), false, t);
        c.drops("Steve", 23, t + 100);
        String s = CombatLog.story(d);
        assertTrue(s.contains("挨了 3 下共 17 点") && s.contains("zombie ×2（9 点）") && s.contains("skeleton「Bob」 ×1（8 点）"), s);
        assertTrue(s.contains("最后一击：skeleton「Bob」（弓"), s);
        assertTrue(s.contains("Alex 6 格（也在挨打，剩 9 血）") && s.contains("组队一起挨打"), s);
        assertTrue(s.contains("掉了 23 件在 minecraft:overworld 10 64 -20"), s);

        assertNull(c.pickup("Steve", "minecraft:overworld", 100, 64, 100, 5, t + 60_000), "离死亡点太远不算");
        CombatLog.Death got = c.pickup("Steve", "minecraft:overworld", 12, 64, -18, 5, t + 60_000);
        assertSame(d, got);
        c.morePickup("Steve", "minecraft:overworld", 11, 64, -19, 7, t + 61_000);
        assertNull(c.pickup("Steve", "minecraft:overworld", 12, 64, -18, 1, t + 62_000), "只报第一次");
        assertTrue(CombatLog.story(d).contains("已回去捡回 12 件（掉了 23 件）"), CombatLog.story(d));
        assertTrue(c.expire(t + 600_000).isEmpty(), "捡回的不会再报丢失");

        // 没回去捡 → 5 分半后 lost；没有掉落物 → grave；死亡不掉落 → none
        CombatLog.Death d2 = c.death("Steve", "摔死了", "minecraft:overworld", 0, 70, 0, List.of(), false, t + 700_000);
        c.drops("Steve", 9, t + 700_100);
        assertEquals(List.of(d2), c.expire(t + 700_000 + 331_000));
        assertTrue(CombatLog.story(d2).contains("死前 30 秒没有记录到受击") && CombatLog.story(d2).contains("大概已经消失"));
        CombatLog.Death d3 = c.death("Alex", "被炸死", "minecraft:overworld", 0, 70, 0, List.of(), false, t + 800_000);
        c.drops("Alex", 0, t + 800_050);
        assertEquals("grave", d3.retrieval);
        assertEquals("none", c.death("Bob", "x", "minecraft:overworld", 0, 0, 0, List.of(), true, t).retrieval);

        // 救下队友：Carl 击杀的僵尸 z9 在 8 秒内把 Dave 打到 5 血
        c.hurt("Dave", hit(t - 3_000, 6, "minecraft:zombie", null, "z9", 5));
        assertEquals(List.of("Dave（当时剩 5/20 血）"), c.rescued("Carl", "z9", t));
        assertTrue(c.rescued("Carl", "z8", t).isEmpty());
        assertTrue(c.rescued("Dave", "z9", t).isEmpty(), "自己杀的不算救自己");
        assertEquals(2, c.json("Steve", 5).size());
    }

    @Test
    void milestonesProjectsAndLedgerPersist() {
        Milestones m = new Milestones(tmp.resolve("ms.json"));
        assertTrue(m.first("Steve", "craft:mekanism:digital_miner"));
        assertFalse(m.first("steve", "craft:mekanism:digital_miner"));
        assertTrue(m.serverFirst("boss:minecraft:wither"));
        m.saveIfDirty(); // 主线程上不写盘，由定时器 / 关服时落盘
        assertFalse(new Milestones(tmp.resolve("ms.json")).first("Steve", "craft:mekanism:digital_miner"), "重启后还记得");
        assertFalse(new Milestones(tmp.resolve("ms.json")).serverFirst("boss:minecraft:wither"));

        AtomicLong now = new AtomicLong(1_000_000_000_000L);
        ProjectStore ps = new ProjectStore(tmp.resolve("projects.json"), now::get);
        String r = ps.create("Steve", "酒馆", "build", "minecraft:overworld", 100, 64, 100, 32, "Steve");
        assertTrue(r.contains("p1") && r.contains("酒馆"), r);
        assertTrue(ps.create("Steve", "  ", "build", null, 0, 0, 0, 0, "Steve").contains("不能为空"));
        assertTrue(ps.join("p1", "Alex").contains("加入"));
        assertTrue(ps.placed("Bob", "minecraft:overworld", 100, 100).isEmpty(), "不是成员不计");
        assertTrue(ps.placed("Alex", "minecraft:overworld", 200, 200).isEmpty(), "范围外不计");
        for (int i = 0; i < 99; i++) assertTrue(ps.placed("Steve", "minecraft:overworld", 110, 90).isEmpty());
        List<ProjectStore.Progress> pr = ps.placed("Alex", "minecraft:overworld", 120, 120);
        assertEquals(1, pr.size());
        assertEquals(100, pr.get(0).milestone());
        assertTrue(ps.note("p1", "Steve", "屋顶做了一半").contains("已记下"));
        assertEquals(1, ps.at("minecraft:overworld", 130, 70).size());
        assertTrue(ps.at("minecraft:the_nether", 100, 100).isEmpty());
        assertTrue(ProjectStore.line(ps.get("p1")).contains("最近：屋顶做了一半") && ProjectStore.line(ps.get("p1")).contains("已放 100 块"),
                ProjectStore.line(ps.get("p1")));
        ps.saveIfDirty();
        ProjectStore again = new ProjectStore(tmp.resolve("projects.json"), now::get);
        assertEquals(1, again.forPlayer("alex", true).size());
        assertTrue(again.create("Steve", "凋灵", "boss", null, 0, 0, 0, 0, "Steve").contains("p2"), "编号接着排");
        assertTrue(again.status("p1", "done", "Steve").contains("完成"));
        assertTrue(again.forPlayer("Steve", true).stream().noneMatch(p -> p.id.equals("p1")));

        BuildLedger l = new BuildLedger(tmp.resolve("ledger.json"), now::get);
        for (int i = 0; i < 30; i++) l.place("Steve", "minecraft:overworld", 100 + i % 3, 100);
        for (int i = 0; i < 10; i++) l.place("Alex", "minecraft:overworld", 120, 100); // 相邻区块
        l.place("Bob", "minecraft:overworld", 1000, 1000);
        JsonObject who = l.who("minecraft:overworld", 101, 101, 1);
        assertEquals(40, who.get("blocks_placed_total").getAsLong());
        assertEquals("Steve", who.getAsJsonArray("builders").get(0).getAsJsonObject().get("player").getAsString());
        assertEquals(75.0, who.getAsJsonArray("builders").get(0).getAsJsonObject().get("share").getAsDouble());
        l.saveIfDirty();
        assertEquals(3, new BuildLedger(tmp.resolve("ledger.json"), now::get).size());
        assertEquals(0, l.who("minecraft:the_end", 0, 0, 1).get("blocks_placed_total").getAsLong());
    }

    /** 工程进提示词；milestone 事件按"玩家|类别"分开冷却，安静中的玩家不交给 AI。 */
    @Test
    void projectsInPromptAndMilestoneEvents() throws Exception {
        TestRig rig = new TestRig(tmp);
        BotConfig cfg = rig.cfg;
        FakeHttp http = rig.http;
        cfg.chat.debounceSeconds = 0;
        cfg.events.debounceSeconds = 1;
        // 假时钟 + 手动推进的定时器：事件合并、冷却都由测试推进，"关掉后不再唤醒"也不用真睡着等
        TestRig.TestClock clock = new TestRig.TestClock();
        TestRig.ManualScheduler timer = new TestRig.ManualScheduler(clock);
        AgentService svc = rig.start(clock, timer);
        svc.setActivity(rig.activity);
        try {
            svc.projects().create("Steve", "酒馆", "build", "minecraft:overworld", 1, 2, 3, 48, "Steve");
            http.ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
            assertTrue(svc.onChat("Steve", "@AI 酒馆屋顶用什么好", false));
            timer.runDue();
            http.awaitRequests(1, 8000);
            String u = http.requests.get(0).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(u.contains("相关玩家正在做的工程") && u.contains("p1「酒馆」"), u);

            http.ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
            svc.onEvent(AgentService.EV_MILESTONE, "Alex|craft", "Alex 第一次造出了 信标");
            svc.onEvent(AgentService.EV_MILESTONE, "Alex|tame", "Alex 驯服了 狼");      // 不同类别：不被冷却挡掉
            svc.onEvent(AgentService.EV_MILESTONE, "Alex|craft", "Alex 第一次造出了 铁砧"); // 同类别冷却内：丢掉
            svc.prefs().setQuiet("Bob", 1, "Bob");
            svc.onEvent(AgentService.EV_MILESTONE, "Bob|find", "Bob 第一次捡到了 钻石"); // 安静中：不交给 AI
            timer.advance(1000); // 事件合并 1 秒
            http.awaitRequests(2, 8000);
            String ev = http.requests.get(1).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(ev.contains("信标") && ev.contains("驯服了 狼") && !ev.contains("铁砧") && !ev.contains("钻石"), ev);
            cfg.events.milestones = false;
            svc.onEvent(AgentService.EV_MILESTONE, "Carl|craft", "Carl 第一次造出了 信标");
            timer.advance(1000);
            // 代理线程按提交顺序逐个运行：随后安排的巡检如果是第 3 个请求，说明前面没有被唤醒的事件运行
            http.ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
            svc.patrolNow();
            assertEquals(3, http.awaitRequests(3, 8000));
            String third = http.requests.get(2).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(third.contains("巡检") && !third.contains("Carl"), "关掉 milestones 后不再唤醒：" + third);
        } finally {
            rig.close();
        }
    }
}
