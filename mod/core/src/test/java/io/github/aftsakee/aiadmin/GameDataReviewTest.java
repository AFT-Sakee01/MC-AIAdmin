package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameData.Drop;
import io.github.aftsakee.aiadmin.gamedata.GameData.ItemInfo;
import io.github.aftsakee.aiadmin.gamedata.GameData.LootTable;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameData.Stack;
import io.github.aftsakee.aiadmin.gamedata.GameDataQueries;
import io.github.aftsakee.aiadmin.gamedata.LootJson;
import io.github.aftsakee.aiadmin.gamedata.RecipeJson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 线上整合包复核（10-08）发现的问题：每条一个用例，数据按真实 JSON 的形状构造。 */
class GameDataReviewTest {
    static final Map<String, List<String>> TAGS = Map.of("mctradepost:bar_nut_seeds", List.of("minecraft:wheat_seeds", "minecraft:melon_seeds"));

    static Recipe recipe(String id, String cat, String json) {
        JsonObject j = JsonParser.parseString(json).getAsJsonObject();
        String type = j.get("type").getAsString();
        RecipeJson.Parsed p = RecipeJson.parse(j, t -> TAGS.getOrDefault(t, List.of()));
        return new Recipe(id, type, cat, RecipeJson.station(cat, type), p.inputs(), p.outputs(), p.shape(), p.key(), p.extra());
    }

    static LootTable loot(String id, String json) {
        return LootJson.parse(id, JsonParser.parseString(json).getAsJsonObject());
    }

    static GameData data(List<Recipe> rs, List<LootTable> lts, String... items) {
        Map<String, ItemInfo> it = new LinkedHashMap<>();
        for (String i : items) it.put(i, new ItemInfo(i, i.substring(i.indexOf(':') + 1), null));
        Map<String, LootTable> lm = new LinkedHashMap<>();
        for (LootTable t : lts) lm.put(t.id(), t);
        return new GameData(it, rs, Map.of(), lm, List.of(), 0);
    }

    @Test
    void recipesWithUnknownInputsAreNotFreeAndModShapesParse() {
        // 烟花火箭这类特殊配方：兜底只拿到产物、没有原料
        Recipe rocket = new Recipe("minecraft:firework_rocket", "minecraft:crafting_special_firework_rocket", "minecraft:crafting", "工作台",
                List.of(), List.of(new Stack("minecraft:firework_rocket", 1, 1, false)), null, null, null);
        // Domum Ornamentum 建筑师切割机：{"block":…, "count":6}
        Recipe paper = recipe("domum_ornamentum:architectscutter/blockpaperwall", "domum_ornamentum:architects_cutter",
                "{\"type\":\"domum_ornamentum:architects_cutter\",\"block\":\"domum_ornamentum:blockpaperwall\",\"count\":6}");
        assertEquals(List.of(new Stack("domum_ornamentum:blockpaperwall", 6, 1, false)), paper.outputs());
        assertTrue(paper.inputsUnknown());
        assertNull(paper.extra(), "block/count 不再进 extra");
        // 顶层 {"tag":…, "count":4}：标签原料 ×4
        Recipe nuts = recipe("mctradepost:bar_nuts", "minecraft:crafting",
                "{\"type\":\"mctradepost:unique_tag_shapeless\",\"tag\":\"mctradepost:bar_nut_seeds\",\"count\":4,"
                        + "\"ingredients\":[{\"item\":\"mctradepost:napkin\"}],\"result\":{\"id\":\"mctradepost:bar_nuts\",\"count\":1}}");
        assertTrue(nuts.inputs().stream().anyMatch(i -> "mctradepost:bar_nut_seeds".equals(i.tag()) && i.count() == 4), nuts.inputs().toString());
        // JustEnoughBreeding 一类的说明数据：刷怪蛋字段不当原料
        Recipe breed = recipe("justenoughbreeding:cow", "justenoughbreeding:breeding",
                "{\"type\":\"justenoughbreeding:breeding\",\"spawn_eggs\":[{\"item\":\"minecraft:cow_spawn_egg\"}],\"breeding_items\":[{\"item\":\"minecraft:wheat\"}]}");
        assertTrue(breed.inputs().stream().noneMatch(i -> i.options().contains("minecraft:cow_spawn_egg")));

        GameData d = data(List.of(rocket, paper, nuts, breed), List.of(), "minecraft:firework_rocket", "domum_ornamentum:blockpaperwall",
                "minecraft:wheat", "mctradepost:bar_nuts");
        CraftPlanner.Plan p = CraftPlanner.plan(d, "minecraft:firework_rocket", 3, Map.of(), CraftPlanner.Options.defaults());
        assertTrue(p.steps().isEmpty());
        assertFalse(p.ready(), "不能显示\"背包里的东西已经够了\"");
        assertEquals("unknown_inputs", p.missing().get(0).reason());
        assertFalse(d.byInput.containsKey("minecraft:wheat"), "无产物的说明数据不进\"能拿来做什么\"");
        JsonObject rf = GameDataQueries.recipeFor(d, "domum_ornamentum:blockpaperwall", 5);
        assertTrue(rf.toString().contains("读不到原料"), rf.toString());
    }

    @Test
    void commonDropsAreGatheredInsteadOfDetours() {
        Recipe leatherFromHide = recipe("minecraft:leather", "minecraft:crafting",
                "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:rabbit_hide\"}},\"pattern\":[\"##\",\"##\"],"
                        + "\"result\":{\"id\":\"minecraft:leather\",\"count\":1}}");
        Recipe stringFromSaddle = recipe("mctradepost:saddle_decon", "mctradepost:deconstruction",
                "{\"type\":\"mctradepost:deconstruction\",\"ingredient\":{\"item\":\"minecraft:saddle\"},"
                        + "\"results\":[{\"id\":\"minecraft:string\",\"count\":4,\"chance\":0.85},{\"id\":\"minecraft:leather\",\"count\":2}]}");
        Recipe planks = recipe("minecraft:oak_planks", "minecraft:crafting",
                "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:oak_log\"}],\"result\":{\"id\":\"minecraft:oak_planks\",\"count\":4}}");
        Recipe stick = recipe("minecraft:stick", "minecraft:crafting",
                "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:oak_planks\"}},\"pattern\":[\"#\",\"#\"],"
                        + "\"result\":{\"id\":\"minecraft:stick\",\"count\":4}}");
        String one = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"%s\"}]}]}";
        List<LootTable> lts = List.of(loot("minecraft:entities/cow", one.formatted("minecraft:leather")),
                loot("minecraft:entities/spider", one.formatted("minecraft:string")),
                loot("minecraft:entities/rabbit", one.formatted("minecraft:rabbit_hide")),
                loot("minecraft:blocks/dead_bush", one.formatted("minecraft:stick")),
                loot("minecraft:blocks/oak_log", one.formatted("minecraft:oak_log")));
        GameData d = data(List.of(leatherFromHide, stringFromSaddle, planks, stick), lts, "minecraft:leather", "minecraft:string",
                "minecraft:rabbit_hide", "minecraft:saddle", "minecraft:stick", "minecraft:oak_planks", "minecraft:oak_log");
        CraftPlanner.Plan leather = CraftPlanner.plan(d, "minecraft:leather", 4, Map.of(), CraftPlanner.Options.defaults());
        assertTrue(leather.steps().isEmpty(), "皮革直接打牛，不拿兔子皮拼");
        assertEquals("gather", leather.missing().get(0).reason());
        CraftPlanner.Plan string = CraftPlanner.plan(d, "minecraft:string", 4, Map.of(), CraftPlanner.Options.defaults());
        assertTrue(string.steps().isEmpty(), "线直接打蜘蛛，不拆鞍");
        // 库存里有兔子皮就照样用配方
        CraftPlanner.Plan held = CraftPlanner.plan(d, "minecraft:leather", 1, Map.of("minecraft:rabbit_hide", 4), CraftPlanner.Options.defaults());
        assertEquals("minecraft:leather", held.steps().get(0).recipe.id());
        // 木棍：枯灌木也掉木棍，但木板做木棍是像样的配方，照常做
        CraftPlanner.Plan sticks = CraftPlanner.plan(d, "minecraft:stick", 4, Map.of(), CraftPlanner.Options.defaults());
        assertEquals("minecraft:stick", sticks.steps().get(sticks.steps().size() - 1).recipe.id());
    }

    @Test
    void lootMutuallyExclusiveEntriesAndNegatedConditions() {
        LootTable slime = loot("minecraft:entities/slime", "{\"type\":\"minecraft:entity\",\"pools\":[{\"rolls\":1,\"entries\":["
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:slime_ball\",\"conditions\":[{\"condition\":\"minecraft:inverted\",\"term\":"
                + "{\"condition\":\"minecraft:damage_source_properties\",\"predicate\":{\"source_entity\":{\"type\":\"minecraft:frog\"}}}}]},"
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:slime_ball\",\"conditions\":[{\"condition\":\"minecraft:damage_source_properties\","
                + "\"predicate\":{\"source_entity\":{\"type\":\"minecraft:frog\"}}}]}]}]}");
        assertEquals(2, slime.drops().size());
        for (Drop x : slime.drops()) assertEquals(1.0, x.chance(), 1e-9, "互斥条件的两个条目各自 100%");
        assertTrue(slime.drops().get(0).conditions().contains("不是特定死亡方式"), slime.drops().get(0).conditions().toString());
        assertTrue(slime.drops().get(1).conditions().contains("特定死亡方式"));

        LootTable leaves = loot("minecraft:blocks/oak_leaves", "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1,\"entries\":["
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\",\"conditions\":[{\"condition\":\"minecraft:inverted\",\"term\":"
                + "{\"condition\":\"minecraft:any_of\",\"terms\":[{\"condition\":\"neoforge:can_item_perform_ability\",\"ability\":\"shears_dig\"},"
                + "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":{\"minecraft:enchantments\":[{\"enchantments\":\"minecraft:silk_touch\"}]}}}]}}]},"
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:oak_leaves\",\"conditions\":[{\"condition\":\"minecraft:any_of\",\"terms\":["
                + "{\"condition\":\"neoforge:can_item_perform_ability\",\"ability\":\"shears_dig\"},"
                + "{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":{\"minecraft:enchantments\":[{\"enchantments\":\"minecraft:silk_touch\"}]}}}]}]}]}]}");
        Drop stick = leaves.drops().get(0), self = leaves.drops().get(1);
        assertEquals(List.of("不用剪刀时 且 不用精准采集时"), stick.conditions(), "德摩根：取反的\"或\"变\"且\"");
        assertFalse(stick.needsSilkOrShears());
        assertEquals(List.of("需要剪刀 或 需要精准采集"), self.conditions());
        assertTrue(self.needsSilkOrShears(), "树叶本体要剪刀或精准采集，不能当成徒手可得");

        // 结构模组的箱子表放在别的路径下：按 JSON 里的 type 分类
        assertEquals("chest", loot("structory_towers:toadstool", "{\"type\":\"minecraft:chest\",\"pools\":[]}").kind());
    }

    @Test
    void sourcesSkipSelfDropLoopsAndInternalTablesAndSearchUsesPath() {
        String one = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"%s\"}]}]}";
        List<LootTable> lts = List.of(loot("minecolonies:blocks/blockhuttownhall", one.formatted("minecolonies:blockhuttownhall")),
                loot("minecolonies:miner/lucky_ore2", one.formatted("minecraft:iron_ore")),
                loot("minecraft:blocks/oak_log", one.formatted("minecraft:oak_log")));
        GameData d = data(List.of(), lts, "minecolonies:blockhuttownhall", "minecraft:iron_ore", "minecraft:oak_log",
                "waystones:warp_dust", "waystones:mossy_waystone");
        List<String> hall = GameDataQueries.topSources(d, "minecolonies:blockhuttownhall", 4);
        assertEquals(1, hall.size(), hall.toString());
        assertTrue(hall.get(0).contains("它掉自己") && hall.get(0).contains("任务奖励"), "只有\"挖自己\"时一句话里说明两种可能：" + hall);
        List<String> ore = GameDataQueries.topSources(d, "minecraft:iron_ore", 4);
        assertTrue(ore.stream().noneMatch(s -> s.contains("lucky_ore")), "殖民地内部表不推荐给玩家：" + ore);
        assertTrue(GameDataQueries.topSources(d, "minecraft:oak_log", 4).get(0).contains("挖 oak_log 方块"));
        assertTrue(GameDataQueries.topSources(d, "waystones:warp_dust", 4).get(0).contains("没有找到常规获取途径"), "什么来源都没有时");
        assertEquals("waystones:mossy_waystone", d.search("waystone", 5).get(0).id(), "包含匹配只比路径，不比命名空间");
        assertEquals(1, d.search("waystone", 5).size());
    }
}
