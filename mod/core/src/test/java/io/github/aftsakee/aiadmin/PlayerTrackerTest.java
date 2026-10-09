package io.github.aftsakee.aiadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.agent.PlayerTracker;
import io.github.aftsakee.aiadmin.agent.PlayerTracker.Sample;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTrackerTest {
    static final long T0 = 1_800_000_000_000L;

    private static Sample at(long t, double x, double y, double z) {
        return new Sample(t, "minecraft:overworld", x, y, z, "minecraft:plains", 20, 20, 20, "minecraft:diamond_pickaxe", "survival", null, false);
    }

    @Test
    void miningIsRecognisedWithCountsAndInventoryChange() {
        PlayerTracker t = new PlayerTracker(60);
        t.join("Steve", T0);
        t.inventory("Steve", Map.of("minecraft:cobblestone", 10, "minecraft:torch", 32), T0);
        for (int i = 0; i < 30; i++) {
            long now = T0 + i * 10_000L;
            t.sample("Steve", at(now, 100 + i * 0.5, 12, 200));
            t.count("Steve", "break", i % 6 == 0 ? "minecraft:deepslate_iron_ore" : "minecraft:deepslate", now);
        }
        long now = T0 + 300_000L;
        t.inventory("Steve", Map.of("minecraft:cobblestone", 10, "minecraft:cobbled_deepslate", 25, "minecraft:raw_iron", 5, "minecraft:torch", 20), now);
        assertTrue(t.guess("Steve", now).contains("挖矿"), t.guess("Steve", now));
        JsonObject a = t.activity("Steve", 15, now);
        assertEquals(25, a.getAsJsonObject("counts").getAsJsonObject("break").get("deepslate").getAsInt());
        assertEquals(5, a.getAsJsonObject("counts").getAsJsonObject("break").get("deepslate_iron_ore").getAsInt());
        JsonObject inv = a.getAsJsonObject("inventory_change");
        assertEquals(5, inv.getAsJsonObject("gained").get("raw_iron").getAsInt());
        assertEquals(12, inv.getAsJsonObject("lost").get("torch").getAsInt());
        assertFalse(inv.getAsJsonObject("gained").has("cobblestone")); // 没变的不列
        assertEquals("overworld 114 12 200", a.getAsJsonObject("now").get("where").getAsString());
        assertTrue(a.getAsJsonArray("path").size() <= 14);
        assertEquals("plains", a.getAsJsonArray("biomes").get(0).getAsString());
    }

    @Test
    void buildingFightingTravelAndAfk() {
        PlayerTracker t = new PlayerTracker(60);
        for (int i = 0; i < 20; i++) t.count("Alex", "place", "minecraft:oak_planks", T0 + i * 1000L);
        for (int i = 0; i < 4; i++) t.count("Alex", "kill", "minecraft:zombie", T0 + i * 1000L);
        t.sample("Alex", at(T0, 0, 70, 0));
        String g = t.guess("Alex", T0 + 30_000L);
        assertTrue(g.contains("建造") && g.contains("战斗"), g);

        for (int i = 0; i <= 30; i++) t.sample("Runner", at(T0 + i * 10_000L, i * 20.0, 70, 0)); // 5 分钟 600 格
        assertTrue(t.guess("Runner", T0 + 300_000L).contains("赶路"), t.guess("Runner", T0 + 300_000L));

        for (int i = 0; i <= 31; i++) t.sample("Afk", at(T0 + i * 10_000L, 5, 70, 5));
        assertTrue(t.guess("Afk", T0 + 310_000L).contains("挂机"));

        for (int i = 0; i < 5; i++) t.count("Farmer", "break", "minecraft:wheat", T0 + i * 1000L);
        t.sample("Farmer", at(T0, 0, 64, 0));
        assertTrue(t.guess("Farmer", T0 + 10_000L).contains("种田"));
    }

    @Test
    void teleportsAndDimensionChangesDoNotCountAsWalking() {
        PlayerTracker t = new PlayerTracker(60);
        t.sample("P", at(T0, 0, 70, 0));
        t.sample("P", at(T0 + 10_000L, 5000, 70, 0)); // 传送
        t.sample("P", new Sample(T0 + 20_000L, "minecraft:the_nether", 625, 70, 0, null, 20, 20, 20, null, "survival", null, false));
        t.sample("P", new Sample(T0 + 30_000L, "minecraft:the_nether", 635, 70, 0, null, 20, 20, 20, null, "survival", null, false));
        JsonObject a = t.activity("P", 5, T0 + 30_000L);
        assertEquals(10, a.get("distance_moved").getAsInt());
        assertEquals(2, a.getAsJsonArray("dimensions").size());
    }

    @Test
    void notableEventsLeaveAndExpiry() {
        PlayerTracker t = new PlayerTracker(60);
        t.sample("Steve", at(T0, 1, 64, 1));
        t.notable("Steve", "container", "打开 chest", "overworld 10 64 10", T0 + 1000);
        t.notable("Steve", "death", "Steve 被僵尸杀死了", null, T0 + 2000);
        t.leave("Steve", T0 + 3000);
        JsonObject a = t.activity("Steve", 10, T0 + 4000);
        assertFalse(a.get("online").getAsBoolean());
        assertEquals(3, a.getAsJsonArray("events").size());
        assertEquals("overworld 1 64 1", a.getAsJsonArray("events").get(1).getAsJsonObject().get("where").getAsString()); // 没给位置时用最后一次采样
        assertTrue(t.guess("Steve", T0 + 4000).startsWith("已下线"));
        t.purge(T0 + 3000 + 61 * 60_000L);
        assertFalse(t.known("Steve"));
        assertTrue(t.activity("Nobody", 5, T0).has("error"));
    }

    @Test
    void oldDataIsPruned() {
        PlayerTracker t = new PlayerTracker(10);
        t.count("P", "break", "minecraft:stone", T0);
        t.sample("P", at(T0, 0, 70, 0));
        t.sample("P", at(T0 + 11 * 60_000L, 0, 70, 0));
        t.count("P", "place", "minecraft:stone", T0 + 11 * 60_000L);
        JsonObject a = t.activity("P", 60, T0 + 11 * 60_000L);
        assertFalse(a.getAsJsonObject("counts").has("break"));
        assertTrue(a.getAsJsonObject("counts").has("place"));
        assertEquals(1, a.getAsJsonArray("path").size());
    }

    @Test
    void repeatedNotablesMergeOnlyWhenSameThingNearbyAndRecent() {
        PlayerTracker t = new PlayerTracker(60);
        t.sample("P", at(T0, 0, 64, 0));
        for (int i = 0; i < 5; i++) t.notable("P", "use", "交互 Lever [lever]", "overworld 10 64 10", T0 + i * 1000L);
        t.notable("P", "ore", "挖到 Diamond Ore [diamond_ore]", "overworld 10 12 10", T0 + 6000);
        t.notable("P", "ore", "挖到 Diamond Ore [diamond_ore]", "overworld 12 11 9", T0 + 7000);   // 同一条矿脉：合并
        t.notable("P", "ore", "挖到 Diamond Ore [diamond_ore]", "overworld 300 11 9", T0 + 8000);  // 太远：新的一条
        t.notable("P", "ore", "挖到 Diamond Ore [diamond_ore]", "the_nether 12 11 9", T0 + 9000);  // 不同维度：新的一条
        t.notable("P", "use", "交互 Lever [lever]", "overworld 10 64 10", T0 + 5 * 60_000L);       // 超过合并窗口：新的一条
        JsonArray ev = t.activity("P", 10, T0 + 5 * 60_000L).getAsJsonArray("events");
        assertEquals(5, ev.size());
        JsonObject lever = ev.get(0).getAsJsonObject();
        assertEquals(5, lever.get("times").getAsInt());
        assertTrue(lever.has("since"));
        assertEquals(2, ev.get(1).getAsJsonObject().get("times").getAsInt());
        assertEquals("overworld 10 12 10", ev.get(1).getAsJsonObject().get("where").getAsString()); // 保留第一次的位置
        assertFalse(ev.get(4).getAsJsonObject().has("times"));
        assertTrue(PlayerTracker.near(null, null));
        assertFalse(PlayerTracker.near("overworld 1 2 3", null));
    }

    @Test
    void areasEventFilterPathPointsAndRecentEvents() {
        PlayerTracker t = new PlayerTracker(60);
        for (int i = 0; i < 60; i++) t.sample("P", at(T0 + i * 10_000L, i, 64, 0));
        for (int i = 0; i < 10; i++) t.count("P", "break", "minecraft:stone", 1, "minecraft:overworld", 100 + i, -40, 300, T0 + i * 1000L);
        t.count("P", "place", "minecraft:torch", 1, "minecraft:overworld", 0, 70, 0, T0);
        t.count("P", "take", "diamond", 5, null, 0, 0, 0, T0);
        t.notable("P", "container", "Chest [chest]：拿走 diamond×5", "overworld 1 64 1", T0 + 1000);
        t.notable("P", "trade", "和 Villager [villager] 交易", "overworld 5 64 5", T0 + 2000);
        t.notable("P", "danger", "放置 TNT [tnt]", "overworld 9 64 9", T0 + 3000);
        long now = T0 + 590_000L;
        JsonObject a = t.activity("P", 10, now, 30, 2, Set.of());
        JsonArray areas = a.getAsJsonArray("areas");
        JsonObject top = areas.get(0).getAsJsonObject();
        assertEquals("break", top.get("kind").getAsString());
        assertEquals(10, top.get("count").getAsInt());
        assertEquals("overworld 104 -40 296", top.get("near").getAsString()); // 区块 (6,18) 的中心、平均高度
        assertTrue(a.getAsJsonArray("path").size() <= 31 && a.getAsJsonArray("path").size() >= 25);
        assertEquals(2, a.getAsJsonArray("events").size());
        assertEquals(1, a.get("events_omitted").getAsInt());
        assertEquals(5, a.getAsJsonObject("counts").getAsJsonObject("take").get("diamond").getAsInt());
        JsonObject only = t.activity("P", 10, now, 12, 40, Set.of("trade"));
        assertEquals(1, only.getAsJsonArray("events").size());
        JsonArray recent = t.recentEvents("P", 2, T0 + 4000, 5 * 60_000L);
        assertEquals(2, recent.size());
        assertEquals("danger", recent.get(1).getAsJsonObject().get("kind").getAsString());
        assertTrue(t.guess("P", T0 + 60_000L).contains("存取物品"));
    }
}
