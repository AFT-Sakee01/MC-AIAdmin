package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.goals.GoalEngine;
import io.github.aftsakee.aiadmin.goals.GoalStore;
import io.github.aftsakee.aiadmin.goals.GoalTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    static final class FakePlayer implements GoalEngine.Facts {
        final String name;
        final Map<String, Integer> inv = new HashMap<>();
        final Set<String> adv = new HashSet<>();
        final Map<String, Integer> stats = new HashMap<>();
        long since;

        FakePlayer(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public Map<String, Integer> inventory() {
            return inv;
        }

        public boolean hasAdvancement(String id) {
            return adv.contains(id);
        }

        public int stat(String type, String key) {
            return stats.getOrDefault(type + "|" + key, 0);
        }

        public long onlineSince() {
            return since;
        }
    }

    static GoalEngine engine(Path dir, boolean colonies) throws Exception {
        GoalTable t = new GoalTable(dir.resolve("goals.json"));
        assertTrue(t.ensure());
        assertFalse(t.ensure(), "已存在的不覆盖");
        GameData d = GameDataTest.data();
        return new GoalEngine(t, new GoalStore(dir.resolve("goals-progress.json")), () -> d, m -> colonies && m.equals("minecolonies"));
    }

    @Test
    void defaultTableParsesAndRequiresModFilters(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, false);
        GoalTable.Table t = e.table().get();
        assertNotNull(t, e.table().error());
        assertTrue(t.settings().enabled());
        assertEquals(20, t.settings().hintIntervalMinutes());
        assertTrue(t.stages().size() >= 12);
        assertFalse(e.stages().stream().anyMatch(s -> s.id().equals("colony")), "没装 minecolonies 时跳过");
        assertTrue(engine(dir.resolve("c"), true).stages().stream().anyMatch(s -> s.id().equals("colony")));
    }

    @Test
    void badEditKeepsLastGoodTableAndReportsError(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, false);
        assertNotNull(e.table().get());
        Path f = dir.resolve("goals.json");
        Files.writeString(f, "{ \"stages\": [ {\"id\":\"x\"} ] }", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        GoalTable.Table t = e.table().get();
        assertNotNull(t);
        assertTrue(t.stages().size() >= 12, "保留旧表");
        assertTrue(e.table().error().contains("done_when"), e.table().error());
    }

    /** 主线程上 get() 不做文件 IO：只返回已读到的表，检查排到后台执行器（同一时间最多排一个）；其他线程照旧当场检查。 */
    @Test
    void mainThreadGetDefersFileCheckToBackground(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, false);
        GoalTable table = e.table();
        int stages = table.get().stages().size();
        java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
        java.util.concurrent.atomic.AtomicBoolean main = new java.util.concurrent.atomic.AtomicBoolean(true);
        table.offMainThread(main::get, queued::add);
        Path f = dir.resolve("goals.json");
        Files.writeString(f, "{\"stages\": [{\"id\": \"only\", \"name\": \"唯一\", \"done_when\": {\"advancement\": \"minecraft:story/root\"}}]}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(stages, table.get().stages().size(), "主线程：先用已读到的表");
        assertEquals(stages, table.get().stages().size());
        assertEquals(1, queued.size(), "检查只排一次");
        queued.poll().run(); // 后台线程执行
        assertEquals(1, table.get().stages().size(), "后台读到新表后主线程看到的是新表");
        Files.writeString(f, "{\"stages\": []}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        main.set(false);
        assertEquals(0, table.get().stages().size(), "不在主线程：当场检查");
        assertTrue(queued.size() <= 1);
    }

    @Test
    void stagesProgressAndCurrentSkipsPastDoneStages(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, true);
        FakePlayer p = new FakePlayer("Steve");
        assertEquals("wood", e.current("Steve").id());
        p.inv.put("minecraft:wooden_pickaxe", 1);
        GoalEngine.News n = e.check(p, 1000);
        assertTrue(n.tell().get(0).contains("木器时代"), n.tell().toString());
        assertEquals("stone", e.current("steve").id(), "名字大小写不敏感");
        // 丢掉木镐也不倒退
        p.inv.clear();
        assertTrue(e.check(p, 2000).tell().isEmpty());
        assertEquals("stone", e.current("Steve").id());
        // 老玩家：直接有了"钻石"进度 → 当前阶段跳到钻石之后
        p.adv.add("minecraft:story/mine_diamond");
        n = e.check(p, 3000);
        assertEquals(1, n.tell().stream().filter(s -> s.contains("达成")).count(), "一次补记多个只提一次");
        assertEquals("nether", e.current("Steve").id());
        // 支线：用过市政厅（统计）
        assertEquals("colony", e.sideGoals("Steve").get(0).id());
        p.stats.put("minecraft:used|minecolonies:blockhuttownhall", 1);
        e.check(p, 4000);
        assertTrue(e.sideGoals("Steve").isEmpty());
        // 持久化（定期检查的结果由一轮检查结束时的 saveIfDirty 写盘）
        e.store().saveIfDirty();
        GoalStore reloaded = new GoalStore(dir.resolve("goals-progress.json"));
        assertTrue(reloaded.get("STEVE").stagesDone.containsKey("colony"));
    }

    @Test
    void personalGoalsAddLimitCompleteAndRemove(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, false);
        GoalStore s = e.store();
        assertNull(s.addPersonal("Alex", "minecraft:iron_pickaxe", 1, "挖钻石用", "Alex", 2, 1));
        assertNull(s.addPersonal("Alex", "minecraft:torch", 16, "", "AI", 2, 2));
        assertNotNull(s.addPersonal("Alex", "minecraft:stick", 1, "", "Alex", 2, 3), "超过上限");
        assertNull(s.addPersonal("Alex", "minecraft:torch", 32, "", "Alex", 2, 4), "同一物品改数量，不占新名额");
        assertEquals(32, s.get("Alex").personal.get(1).count);

        FakePlayer p = new FakePlayer("Alex");
        p.inv.put("minecraft:torch", 40);
        GoalEngine.News n = e.check(p, 10_000);
        assertTrue(n.tell().stream().anyMatch(x -> x.contains("个人目标完成")), n.tell().toString());
        assertTrue(n.announce().isEmpty(), "默认不全服播报");
        List<GoalStore.Personal> ordered = GoalStore.ordered(s.get("Alex").personal);
        assertEquals("minecraft:iron_pickaxe", ordered.get(0).item, "未完成在前");
        assertNotEquals(0, ordered.get(1).done);

        assertEquals("minecraft:iron_pickaxe", s.removePersonal("Alex", "1").item);
        assertNull(s.removePersonal("Alex", "9"));
        s.prune(10_000 + GoalStore.DONE_KEEP_MS + 1);
        assertTrue(s.get("Alex").personal.isEmpty(), "完成一天后清掉");
    }

    @Test
    void nextLinesUsePlannerAndHintsAreRateLimited(@TempDir Path dir) throws Exception {
        GoalEngine e = engine(dir, false);
        FakePlayer p = new FakePlayer("Kai");
        p.adv.add("minecraft:story/upgrade_tools"); // 当前阶段 = iron（3 个铁锭）
        e.check(p, 0);
        List<String> next = e.nextLines(p, 5, 3);
        assertTrue(next.get(0).contains("来硬的"), next.toString());
        assertTrue(next.stream().anyMatch(l -> l.startsWith("还缺：") && l.contains("Raw iron")), next.toString());

        long min = 60_000L;
        p.since = 0;
        assertTrue(e.hint(p, 4 * min).isEmpty(), "上线不满 5 分钟");
        List<String> h = e.hint(p, 6 * min);
        assertFalse(h.isEmpty());
        assertTrue(h.get(h.size() - 1).contains("/goal hints off"));
        assertTrue(e.hint(p, 20 * min).isEmpty(), "20 分钟内不重复");
        assertTrue(e.hint(p, 30 * min).isEmpty(), "内容没变，60 分钟内不重复");
        p.inv.put("minecraft:raw_iron", 1); // 进度变了
        assertFalse(e.hint(p, 30 * min).isEmpty());
        e.store().update("Kai", q -> q.hintsOff = true);
        assertTrue(e.hint(p, 200 * min).isEmpty(), "玩家自己关了");

        // 个人目标优先于阶段
        e.store().update("Kai", q -> q.hintsOff = false);
        e.store().addPersonal("Kai", "minecraft:torch", 4, "", "Kai", 10, 1);
        assertTrue(e.nextLines(p, 5, 3).get(0).startsWith("【个人目标】"));

        JsonObject st = e.status("Kai", p);
        assertTrue(st.get("current_stage").getAsString().startsWith("iron"));
        assertEquals(1, st.getAsJsonArray("personal_goals").size());
        assertTrue(st.has("next_suggestion"));
        assertTrue(e.status("Kai", null).has("note"));
    }

    @Test
    void conditionCombinatorsAndTagItems(@TempDir Path dir) {
        GoalTable.Table t = GoalTable.parse("""
                {"stages":[{"id":"a","done_when":{"all":[{"item":"#minecraft:planks","count":3},
                  {"any":[{"advancement":"x:y"},{"stat":"minecraft:custom","key":"minecraft:jump","min":10}]}]}}]}""");
        GameData d = GameDataTest.data();
        FakePlayer p = new FakePlayer("P");
        p.inv.put("minecraft:oak_planks", 1);
        p.inv.put("minecraft:spruce_planks", 2);
        assertFalse(GoalEngine.met(t.stages().get(0).doneWhen(), p, d));
        p.stats.put("minecraft:custom|minecraft:jump", 10);
        assertTrue(GoalEngine.met(t.stages().get(0).doneWhen(), p, d));
        assertThrows(RuntimeException.class, () -> GoalTable.parse("{\"stages\":[{\"id\":\"a\",\"done_when\":{\"foo\":1}}]}"));
        assertThrows(RuntimeException.class, () -> GoalTable.parse("{\"stages\":[{\"id\":\"a\",\"done_when\":{\"item\":\"x\"}},{\"id\":\"a\",\"done_when\":{\"item\":\"x\"}}]}"));
    }

    // ———————————————————— 1.0.0.27 代码审查修复 ————————————————————

    static List<Path> corruptCopies(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith("goals-progress.json.corrupt-")).toList();
        }
    }

    @Test
    void corruptProgressFileIsQuarantinedNotOverwritten(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("goals-progress.json");
        String garbage = "{\"players\": {\"steve\": {\"stagesDone\": {\"wood\": 1"; // 写一半断电留下的半截文件
        Files.writeString(f, garbage, StandardCharsets.UTF_8);
        GoalStore s = new GoalStore(f);
        assertNotNull(s.loadError());
        assertTrue(s.get("Steve").stagesDone.isEmpty(), "读坏了从空开始");
        s.update("Alex", q -> q.stagesDone.put("wood", 5L)); // 之后照常保存
        s.saveIfDirty();
        List<Path> bad = corruptCopies(dir);
        assertEquals(1, bad.size(), "原文件改名保留");
        assertEquals(garbage, Files.readString(bad.get(0), StandardCharsets.UTF_8), "坏文件内容没被覆盖");
        assertTrue(new GoalStore(f).get("alex").stagesDone.containsKey("wood"), "新文件可以正常读回");
        assertNull(s.saveError());

        // 结构不对（players 是数组）：同样隔离
        Path g = dir.resolve("g2").resolve("goals-progress.json");
        Files.createDirectories(g.getParent());
        Files.writeString(g, "{\"players\": []}", StandardCharsets.UTF_8);
        assertTrue(new GoalStore(g).loadError().contains("players"));
        assertEquals(1, corruptCopies(g.getParent()).size());
    }

    @Test
    void badPlayerRecordsAndEmptyGoalItemsAreSkippedIndividually(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("goals-progress.json");
        String json = """
                {"players": {
                  "nobody": null,
                  "typo": {"name": "Typo", "stagesDone": "wood"},
                  "steve": {"name": "Steve", "stagesDone": {"wood": 1000, "stone": null}},
                  "alex": {"name": "Alex", "personal": [{"count": 3}, null, {"item": "", "count": 1},
                                                        {"item": "minecraft:torch", "count": 2, "created": 7}]}
                }}""";
        Files.writeString(f, json, StandardCharsets.UTF_8);
        GoalStore s = new GoalStore(f);
        assertEquals(Map.of("wood", 1000L), s.get("steve").stagesDone, "一个玩家的坏值不影响其它玩家（旧实现整份清空）");
        List<GoalStore.Personal> alex = s.get("alex").personal;
        assertEquals(1, alex.size(), "物品为空的个人目标丢弃");
        assertEquals("minecraft:torch", alex.get(0).item);
        assertTrue(s.get("typo").stagesDone.isEmpty(), "类型不对的玩家记录整条跳过");
        assertTrue(s.loadError().contains("跳过"), s.loadError());
        List<Path> bak = corruptCopies(dir);
        assertEquals(1, bak.size(), "跳过部分记录前先备份原文件");
        assertEquals(json, Files.readString(bak.get(0), StandardCharsets.UTF_8));

        // 读进来的个人目标可以正常判定（物品为空时 GoalEngine.count 每次都会抛异常）
        GoalTable t = new GoalTable(dir.resolve("goals.json"));
        t.ensure();
        GameData d = GameDataTest.data();
        GoalEngine e = new GoalEngine(t, s, () -> d, m -> false);
        FakePlayer p = new FakePlayer("Alex");
        p.inv.put("minecraft:torch", 2);
        assertTrue(e.check(p, 1000).tell().stream().anyMatch(x -> x.contains("个人目标完成")));
        assertNotNull(s.addPersonal("Alex", " ", 1, "", "Alex", 10, 1), "不接受空物品");
    }

    @Test
    void tableFieldTypeErrorsAreWarnedNotSilentlyDefaulted(@TempDir Path dir) throws Exception {
        GoalTable.Table t = GoalTable.parse("""
                {"enabled": "false", "check_interval_seconds": "60", "hints": {"enabled": "yes"},
                 "stages": [
                   {"name": "无名", "done_when": {"item": "minecraft:stick", "count": "3"}},
                   {"id": "b", "optional": "true", "done_when": {"advancement": "x:y"}}
                 ]}""");
        assertFalse(t.settings().enabled(), "\"false\" 按 false 理解，不再被当成 true");
        assertEquals(60, t.settings().checkIntervalSeconds());
        assertTrue(t.settings().hintsEnabled(), "看不出意思的取默认");
        assertEquals("stage1", t.stages().get(0).id(), "缺 id 仍按位置兼容");
        assertEquals(new GoalTable.Cond.Item("minecraft:stick", 3), t.stages().get(0).doneWhen(), "\"3\" 按 3 理解，不再变成 1");
        assertTrue(t.stages().get(1).optional());
        String w = String.join("\n", t.warnings());
        assertTrue(w.contains("enabled 应为布尔值"), w);
        assertTrue(w.contains("check_interval_seconds 应为整数"), w);
        assertTrue(w.contains("hints.enabled 应为布尔值") && w.contains("按默认 true"), w);
        assertTrue(w.contains("count 应为整数"), w);
        assertTrue(w.contains("缺少 id"), w);
        assertTrue(GoalTable.parse(GoalTable.DEFAULT).warnings().isEmpty(), "默认表没有警告");

        // 警告也通过 GoalTable.warnings() / goal_status 交给服主
        Path f = dir.resolve("goals.json");
        Files.writeString(f, "{\"stages\":[{\"done_when\":{\"advancement\":\"x:y\"}}]}", StandardCharsets.UTF_8);
        GoalTable gt = new GoalTable(f);
        assertNotNull(gt.get());
        assertTrue(gt.warnings().get(0).contains("缺少 id"));
        GameData d = GameDataTest.data();
        JsonObject st = new GoalEngine(gt, new GoalStore(dir.resolve("p.json")), () -> d, m -> false).status("P", null);
        assertTrue(st.has("table_warnings"), st.toString());

        // 顶层不是对象：错误信息不把整个文件塞进去
        String huge = "[" + "1,".repeat(5000) + "1]";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> GoalTable.parse(huge));
        assertTrue(ex.getMessage().length() < 120, ex.getMessage());
        Files.writeString(f, huge, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertNotNull(gt.get(), "保留上次的内容");
        assertTrue(gt.error().length() < 300, gt.error());
    }

    @Test
    void hintSkipsReplanningWhenNothingChanged(@TempDir Path dir) throws Exception {
        GoalTable t = new GoalTable(dir.resolve("goals.json"));
        t.ensure();
        GameData d = GameDataTest.data();
        AtomicInteger gets = new AtomicInteger();
        GoalEngine e = new GoalEngine(t, new GoalStore(dir.resolve("goals-progress.json")), () -> {
            gets.incrementAndGet();
            return d;
        }, m -> false);
        FakePlayer p = new FakePlayer("Kai");
        p.adv.add("minecraft:story/upgrade_tools");
        e.check(p, 0);
        long min = 60_000L;
        assertFalse(e.hint(p, 6 * min).isEmpty());
        // 间隔到了但内容没变：只取一次快照比签名（hint 自己），不再调用 nextLines（它还会再取一次并跑合成规划）
        int before = gets.get();
        assertTrue(e.hint(p, 30 * min).isEmpty(), "内容没变，60 分钟内不重复");
        assertEquals(before + 1, gets.get(), "没有重算路线");
        // 背包变了：重算并提示
        p.inv.put("minecraft:raw_iron", 1);
        before = gets.get();
        assertFalse(e.hint(p, 30 * min).isEmpty());
        assertEquals(before + 2, gets.get(), "签名变了才重算");
        // 到了 repeat_unchanged_minutes：复用上次算好的内容照常重复提示
        before = gets.get();
        List<String> again = e.hint(p, 100 * min);
        assertFalse(again.isEmpty());
        assertEquals(before + 1, gets.get());
    }
}
