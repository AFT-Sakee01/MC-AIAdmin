package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameData.Drop;
import io.github.aftsakee.aiadmin.gamedata.GameData.ItemInfo;
import io.github.aftsakee.aiadmin.gamedata.GameData.LootTable;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameDataQueries;
import io.github.aftsakee.aiadmin.gamedata.LootJson;
import io.github.aftsakee.aiadmin.gamedata.RecipeJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameDataTest {
    static final Map<String, List<String>> TAGS = new LinkedHashMap<>();

    static {
        TAGS.put("minecraft:planks", List.of("minecraft:oak_planks", "minecraft:spruce_planks"));
        TAGS.put("minecraft:logs", List.of("minecraft:oak_log", "minecraft:spruce_log"));
        TAGS.put("minecraft:oak_logs", List.of("minecraft:oak_log"));
        TAGS.put("minecraft:iron_ores", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
        TAGS.put("c:ingots/iron", List.of("minecraft:iron_ingot"));
    }

    static Recipe recipe(String id, String json) {
        JsonObject j = JsonParser.parseString(json).getAsJsonObject();
        String type = j.get("type").getAsString();
        String cat = switch (type) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" -> "minecraft:crafting";
            case "minecraft:smithing_transform" -> "minecraft:smithing";
            default -> type;
        };
        RecipeJson.Parsed p = RecipeJson.parse(j, t -> TAGS.getOrDefault(t, List.of()));
        return new Recipe(id, type, cat, RecipeJson.station(cat, type), p.inputs(), p.outputs(), p.shape(), p.key(), p.extra(), p.droppedSlots());
    }

    static final String OAK_PLANKS = "{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"building\",\"group\":\"planks\","
            + "\"ingredients\":[{\"tag\":\"minecraft:oak_logs\"}],\"result\":{\"count\":4,\"id\":\"minecraft:oak_planks\"}}";
    static final String STICK = "{\"type\":\"minecraft:crafting_shaped\",\"category\":\"misc\",\"key\":{\"#\":{\"tag\":\"minecraft:planks\"}},"
            + "\"pattern\":[\"#\",\"#\"],\"result\":{\"count\":4,\"id\":\"minecraft:stick\"}}";
    static final String IRON_PICK = "{\"type\":\"minecraft:crafting_shaped\",\"category\":\"equipment\",\"key\":{\"#\":{\"item\":\"minecraft:stick\"},"
            + "\"X\":{\"tag\":\"c:ingots/iron\"}},\"pattern\":[\"XXX\",\" # \",\" # \"],\"result\":{\"count\":1,\"id\":\"minecraft:iron_pickaxe\"}}";
    static final String SMELT_RAW = "{\"type\":\"minecraft:smelting\",\"category\":\"misc\",\"cookingtime\":200,\"experience\":0.7,"
            + "\"ingredient\":{\"item\":\"minecraft:raw_iron\"},\"result\":{\"count\":1,\"id\":\"minecraft:iron_ingot\"}}";
    static final String SMELT_ORE = "{\"type\":\"minecraft:smelting\",\"category\":\"misc\",\"cookingtime\":200,\"experience\":0.7,"
            + "\"ingredient\":{\"item\":\"minecraft:deepslate_iron_ore\"},\"result\":{\"count\":1,\"id\":\"minecraft:iron_ingot\"}}";
    static final String INGOT_FROM_BLOCK = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:iron_block\"}],"
            + "\"result\":{\"count\":9,\"id\":\"minecraft:iron_ingot\"}}";
    static final String BLOCK_FROM_INGOT = "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:iron_ingot\"}},"
            + "\"pattern\":[\"###\",\"###\",\"###\"],\"result\":{\"count\":1,\"id\":\"minecraft:iron_block\"}}";
    static final String INGOT_FROM_NUGGETS = "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:iron_nugget\"}},"
            + "\"pattern\":[\"###\",\"###\",\"###\"],\"result\":{\"count\":1,\"id\":\"minecraft:iron_ingot\"}}";
    static final String NUGGETS = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}],"
            + "\"result\":{\"count\":9,\"id\":\"minecraft:iron_nugget\"}}";
    static final String RAW_FROM_BLOCK = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:raw_iron_block\"}],"
            + "\"result\":{\"count\":9,\"id\":\"minecraft:raw_iron\"}}";
    static final String RAW_BLOCK = "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:raw_iron\"}},"
            + "\"pattern\":[\"###\",\"###\",\"###\"],\"result\":{\"count\":1,\"id\":\"minecraft:raw_iron_block\"}}";
    static final String SMITH = "{\"type\":\"minecraft:smithing_transform\",\"addition\":{\"tag\":\"c:ingots/netherite\"},"
            + "\"base\":{\"item\":\"minecraft:diamond_pickaxe\"},\"result\":{\"count\":1,\"id\":\"minecraft:netherite_pickaxe\"},"
            + "\"template\":{\"item\":\"minecraft:netherite_upgrade_smithing_template\"}}";
    static final String CREATE_MIX = "{\"type\":\"create:mixing\",\"heat_requirement\":\"heated\",\"ingredients\":["
            + "{\"tag\":\"c:ingots/iron\"},{\"item\":\"minecraft:coal\"},{\"type\":\"fluid_stack\",\"amount\":250,\"fluid\":\"minecraft:lava\"}],"
            + "\"results\":[{\"id\":\"create:andesite_alloy\",\"count\":2},{\"id\":\"minecraft:iron_nugget\",\"count\":1,\"chance\":0.25}]}";
    static final String SHAPELESS_ALT = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[[{\"item\":\"minecraft:coal\"},"
            + "{\"item\":\"minecraft:charcoal\"}],{\"item\":\"minecraft:stick\"},{\"item\":\"minecraft:stick\"}],"
            + "\"result\":{\"count\":4,\"id\":\"minecraft:torch\"}}";
    static final String SIZED = "{\"type\":\"somemod:press\",\"input\":{\"ingredient\":{\"item\":\"minecraft:iron_ingot\"},\"count\":3},"
            + "\"output\":{\"id\":\"somemod:iron_plate\",\"count\":1},\"time\":100}";

    @Test
    void parsesVanillaShapedShapelessSmeltingSmithing() {
        Recipe pick = recipe("minecraft:iron_pickaxe", IRON_PICK);
        assertEquals("工作台", pick.station());
        assertEquals(List.of("XXX", " # ", " # "), pick.shape());
        assertEquals(2, pick.inputs().size());
        var x = pick.inputs().stream().filter(i -> "c:ingots/iron".equals(i.tag())).findFirst().orElseThrow();
        assertEquals(3, x.count());
        assertEquals(List.of("minecraft:iron_ingot"), x.options());
        var stick = pick.inputs().stream().filter(i -> i.options().contains("minecraft:stick")).findFirst().orElseThrow();
        assertEquals(2, stick.count());
        assertEquals(1, pick.surely("minecraft:iron_pickaxe"));

        Recipe smelt = recipe("minecraft:iron_ingot_from_smelting_raw_iron", SMELT_RAW);
        assertEquals("熔炉", smelt.station());
        assertEquals(List.of("minecraft:raw_iron"), smelt.inputs().get(0).options());
        assertNotNull(smelt.extra());
        assertTrue(smelt.extra().contains("cookingtime"));

        Recipe smith = recipe("minecraft:netherite_pickaxe_smithing", SMITH);
        assertEquals("锻造台", smith.station());
        assertEquals(3, smith.inputs().size());

        Recipe torch = recipe("minecraft:torch", SHAPELESS_ALT);
        assertEquals(2, torch.inputs().size(), "两根木棍合并成一个槽×2");
        assertEquals(List.of("minecraft:coal", "minecraft:charcoal"), torch.inputs().get(0).options());
        assertEquals(2, torch.inputs().get(1).count());
    }

    @Test
    void parsesModMachineFluidsChancesAndSizedIngredients() {
        Recipe mix = recipe("create:mixing/andesite_alloy", CREATE_MIX);
        assertTrue(mix.station().contains("create:mixing"));
        assertTrue(mix.inputs().stream().anyMatch(i -> i.fluid() && i.options().contains("minecraft:lava") && i.count() == 250));
        assertEquals(2, mix.surely("create:andesite_alloy"));
        assertEquals(0, mix.surely("minecraft:iron_nugget"), "概率产出不算确定产出");
        assertEquals(0.25, mix.expected("minecraft:iron_nugget"), 1e-9);
        assertTrue(mix.extra().contains("heated"));

        Recipe press = recipe("somemod:press/iron_plate", SIZED);
        assertEquals(3, press.inputs().get(0).count());
        assertEquals(1, press.surely("somemod:iron_plate"));
    }

    static final String IRON_ORE_LOOT = "{\"type\":\"minecraft:block\",\"pools\":[{\"bonus_rolls\":0.0,\"entries\":[{\"type\":\"minecraft:alternatives\","
            + "\"children\":[{\"type\":\"minecraft:item\",\"conditions\":[{\"condition\":\"minecraft:match_tool\",\"predicate\":{\"predicates\":"
            + "{\"minecraft:enchantments\":[{\"enchantments\":\"minecraft:silk_touch\",\"levels\":{\"min\":1}}]}}}],\"name\":\"minecraft:iron_ore\"},"
            + "{\"type\":\"minecraft:item\",\"functions\":[{\"enchantment\":\"minecraft:fortune\",\"formula\":\"minecraft:ore_drops\","
            + "\"function\":\"minecraft:apply_bonus\"},{\"function\":\"minecraft:explosion_decay\"}],\"name\":\"minecraft:raw_iron\"}]}],\"rolls\":1.0}]}";
    static final String ZOMBIE_LOOT = "{\"type\":\"minecraft:entity\",\"pools\":[{\"bonus_rolls\":0.0,\"entries\":[{\"type\":\"minecraft:item\","
            + "\"functions\":[{\"add\":false,\"count\":{\"type\":\"minecraft:uniform\",\"max\":2.0,\"min\":0.0},\"function\":\"minecraft:set_count\"},"
            + "{\"count\":{\"type\":\"minecraft:uniform\",\"max\":1.0,\"min\":0.0},\"enchantment\":\"minecraft:looting\",\"function\":\"minecraft:enchanted_count_increase\"}],"
            + "\"name\":\"minecraft:rotten_flesh\"}],\"rolls\":1.0},{\"bonus_rolls\":0.0,\"conditions\":[{\"condition\":\"minecraft:killed_by_player\"},"
            + "{\"condition\":\"minecraft:random_chance_with_enchanted_bonus\",\"enchanted_chance\":{\"type\":\"minecraft:linear\",\"base\":0.035,"
            + "\"per_level_above_first\":0.01},\"enchantment\":\"minecraft:looting\",\"unenchanted_chance\":0.025}],\"entries\":["
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:iron_ingot\"},{\"type\":\"minecraft:item\",\"name\":\"minecraft:carrot\"},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:potato\"}],\"rolls\":1.0}]}";
    static final String CHEST_LOOT = "{\"type\":\"minecraft:chest\",\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:saddle\",\"weight\":20},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:golden_apple\",\"weight\":15},{\"type\":\"minecraft:empty\",\"weight\":15},"
            + "{\"type\":\"minecraft:tag\",\"name\":\"minecraft:planks\",\"expand\":true,\"weight\":10}],"
            + "\"rolls\":{\"type\":\"minecraft:uniform\",\"min\":1.0,\"max\":3.0}},{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:chests/extra\"}]}]}";

    static LootTable loot(String id, String json) {
        return LootJson.parse(id, JsonParser.parseString(json).getAsJsonObject(), t -> TAGS.getOrDefault(t, List.of()));
    }

    @Test
    void lootTablesFlattenWithChancesAndConditions() {
        LootTable ore = loot("minecraft:blocks/iron_ore", IRON_ORE_LOOT);
        assertEquals("block", ore.kind());
        assertEquals(2, ore.drops().size());
        Drop silk = ore.drops().get(0), raw = ore.drops().get(1);
        assertEquals("minecraft:iron_ore", silk.item());
        assertTrue(silk.conditions().contains("需要精准采集"));
        assertTrue(silk.alternative());
        assertEquals("minecraft:raw_iron", raw.item());
        assertTrue(raw.conditions().isEmpty());
        assertTrue(raw.functions().contains("时运加成"));

        LootTable z = loot("minecraft:entities/zombie", ZOMBIE_LOOT);
        Drop flesh = z.drops().stream().filter(d -> d.item().equals("minecraft:rotten_flesh")).findFirst().orElseThrow();
        assertEquals("0-2", flesh.count());
        assertEquals(1.0, flesh.chance(), 1e-9);
        assertTrue(flesh.functions().contains("抢夺加成"));
        Drop iron = z.drops().stream().filter(d -> d.item().equals("minecraft:iron_ingot")).findFirst().orElseThrow();
        assertEquals(0.025 / 3, iron.chance(), 1e-9, "1/3 权重 × 2.5%");
        assertTrue(iron.conditions().contains("需要玩家击杀"));
        assertTrue(iron.conditions().contains("抢夺可提高概率"));

        LootTable c = loot("minecraft:chests/simple_dungeon", CHEST_LOOT);
        assertEquals("chest", c.kind());
        Drop saddle = c.drops().stream().filter(d -> d.item().equals("minecraft:saddle")).findFirst().orElseThrow();
        // 空条目也占权重；#planks 是 expand = true 的标签，2 种木板各占 10：总权重 20 + 15 + 15 + 10×2 = 70（不是 60）
        assertEquals(20.0 / 70, saddle.chance(), 1e-9, "空条目也占权重，展开的标签按成员数计权重");
        assertEquals("1-3", saddle.rolls());
        assertTrue(c.drops().stream().anyMatch(d -> d.tagEntry() && d.item().equals("minecraft:planks")));
        assertEquals(List.of("minecraft:chests/extra"), c.refs());
    }

    static GameData data() {
        Map<String, ItemInfo> items = new LinkedHashMap<>();
        for (String id : List.of("oak_log", "spruce_log", "oak_planks", "spruce_planks", "stick", "iron_ingot", "iron_block", "iron_nugget",
                "raw_iron", "raw_iron_block", "iron_ore", "deepslate_iron_ore", "iron_pickaxe", "coal", "charcoal", "torch", "lava_bucket")) {
            String en = id.replace('_', ' ');
            items.put("minecraft:" + id, new ItemInfo("minecraft:" + id, Character.toUpperCase(en.charAt(0)) + en.substring(1), null));
        }
        items.put("create:andesite_alloy", new ItemInfo("create:andesite_alloy", "Andesite Alloy", "安山合金"));
        Map<String, List<String>> tags = new LinkedHashMap<>(TAGS);
        List<Recipe> rs = new ArrayList<>(List.of(
                recipe("minecraft:oak_planks", OAK_PLANKS),
                recipe("minecraft:stick", STICK),
                recipe("minecraft:iron_pickaxe", IRON_PICK),
                recipe("minecraft:iron_ingot_from_smelting_raw_iron", SMELT_RAW),
                recipe("minecraft:iron_ingot_from_smelting_deepslate_iron_ore", SMELT_ORE),
                recipe("minecraft:iron_ingot_from_iron_block", INGOT_FROM_BLOCK),
                recipe("minecraft:iron_block", BLOCK_FROM_INGOT),
                recipe("minecraft:iron_ingot_from_nuggets", INGOT_FROM_NUGGETS),
                recipe("minecraft:iron_nugget", NUGGETS),
                recipe("minecraft:torch", SHAPELESS_ALT),
                recipe("minecraft:raw_iron", RAW_FROM_BLOCK),
                recipe("minecraft:raw_iron_block", RAW_BLOCK),
                recipe("create:mixing/andesite_alloy", CREATE_MIX)));
        Map<String, LootTable> loot = new LinkedHashMap<>();
        loot.put("minecraft:blocks/iron_ore", loot("minecraft:blocks/iron_ore", IRON_ORE_LOOT));
        loot.put("minecraft:blocks/deepslate_iron_ore", loot("minecraft:blocks/deepslate_iron_ore",
                IRON_ORE_LOOT.replace("minecraft:iron_ore", "minecraft:deepslate_iron_ore")));
        loot.put("minecraft:entities/zombie", loot("minecraft:entities/zombie", ZOMBIE_LOOT));
        // 模组的特殊战利品表偶尔给铁矿石（MineColonies 矿工的"幸运矿石"）：不能因此把铁矿石当成好拿的
        loot.put("minecolonies:miner/lucky_ore", loot("minecolonies:miner/lucky_ore",
                "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:deepslate_iron_ore\",\"weight\":20},"
                        + "{\"type\":\"minecraft:empty\",\"weight\":80}]}]}"));
        loot.put("minecraft:blocks/oak_log", loot("minecraft:blocks/oak_log",
                "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:oak_log\"}]}]}"));
        return new GameData(items, rs, tags, loot, List.of(), 0);
    }

    @Test
    void indexesSearchAndResolve() {
        GameData d = data();
        assertEquals(4, d.byOutput.get("minecraft:iron_ingot").size(), "4 条配方产出铁锭");
        assertTrue(d.byInput.get("minecraft:iron_ingot").stream().anyMatch(r -> r.id().equals("minecraft:iron_pickaxe")), "标签原料也进反查");
        assertTrue(d.sources.get("minecraft:raw_iron").size() >= 2);
        assertFalse(d.sources.containsKey("minecraft:oak_planks"));
        assertEquals("minecraft:iron_ingot", d.resolveItem("iron_ingot"));
        assertEquals("minecraft:iron_ingot", d.resolveItem("Iron ingot"));
        assertEquals("create:andesite_alloy", d.resolveItem("安山合金"));
        assertNull(d.resolveItem("iron"), "模糊词不直接认定");
        assertEquals("minecraft:iron_ingot", d.search("iron", 10).stream().map(ItemInfo::id).filter(s -> s.equals("minecraft:iron_ingot")).findFirst().orElse(null));
        assertEquals("Andesite Alloy / 安山合金", d.items.get("create:andesite_alloy").label());
    }

    @Test
    void planIronPickaxeFromNothingPrefersSmeltingAndAvoidsBlockCycle() {
        GameData d = data();
        CraftPlanner.Plan p = CraftPlanner.plan(d, "minecraft:iron_pickaxe", 1, Map.of(), CraftPlanner.Options.defaults());
        List<String> order = p.steps().stream().map(s -> s.recipe.id()).toList();
        // 木板 → 木棍；铁锭用熔炉烧粗铁（不是铁块拆、不是铁粒拼、也不是要精准采集的深层铁矿）
        assertTrue(order.contains("minecraft:iron_ingot_from_smelting_raw_iron"), order.toString());
        assertFalse(order.contains("minecraft:iron_ingot_from_iron_block"));
        assertFalse(order.contains("minecraft:iron_ingot_from_nuggets"));
        assertTrue(order.indexOf("minecraft:oak_planks") < order.indexOf("minecraft:stick"));
        assertEquals("minecraft:iron_pickaxe", order.get(order.size() - 1));
        Map<String, Integer> miss = new LinkedHashMap<>();
        p.missing().forEach(m -> miss.put(m.item(), m.count()));
        assertEquals(Map.of("minecraft:raw_iron", 3, "minecraft:oak_log", 1), miss);
        assertEquals("gather", p.missing().get(0).reason(), "粗铁有配方（粗铁块拆），但能直接挖铁矿 → 记为直接采集");
        assertEquals("no_recipe", p.missing().get(1).reason());
        // 1 原木 → 4 木板，木棍用 2 个，剩 2 个；木棍做出 4 根用 2 根，剩 2 根
        assertEquals(2, p.leftovers().get("minecraft:oak_planks"));
        assertEquals(2, p.leftovers().get("minecraft:stick"));
        assertFalse(p.ready());
        List<String> lines = GameDataQueries.planLines(d, p, 10, 5);
        assertTrue(lines.get(lines.size() - 1).startsWith("还缺："), lines.toString());
        assertTrue(lines.get(lines.size() - 1).contains("挖"), lines.toString());
    }

    @Test
    void planUsesInventoryFirstAndPrefersHeldBlock() {
        GameData d = data();
        // 背包里有一个铁块 + 木棍：直接拆铁块（库存够用时可逆配方也优先）
        CraftPlanner.Plan p = CraftPlanner.plan(d, "minecraft:iron_pickaxe", 1,
                Map.of("minecraft:iron_block", 1, "minecraft:stick", 5), CraftPlanner.Options.defaults());
        List<String> order = p.steps().stream().map(s -> s.recipe.id()).toList();
        assertEquals(List.of("minecraft:iron_ingot_from_iron_block", "minecraft:iron_pickaxe"), order);
        assertTrue(p.ready());
        assertEquals(6, p.leftovers().get("minecraft:iron_ingot"));
        assertEquals(2, p.usedFromInventory().get("minecraft:stick"));

        // 背包里有粗铁块：拆它（库存够用时可逆配方照用）
        CraftPlanner.Plan blk = CraftPlanner.plan(d, "minecraft:iron_ingot", 3, Map.of("minecraft:raw_iron_block", 1), CraftPlanner.Options.defaults());
        assertEquals(List.of("minecraft:raw_iron", "minecraft:iron_ingot_from_smelting_raw_iron"), blk.steps().stream().map(s -> s.recipe.id()).toList());
        assertTrue(blk.ready());

        // 已经有 2 把：只做差额以外不做——planner 的 count 是"要做几把"，已有数量单独报告
        CraftPlanner.Plan have = CraftPlanner.plan(d, "minecraft:iron_pickaxe", 1, Map.of("minecraft:iron_pickaxe", 1), CraftPlanner.Options.defaults());
        assertTrue(have.steps().isEmpty());
        assertEquals(1, have.haveAlready());
    }

    @Test
    void planFluidsForcedRecipeAndNoCycles() {
        GameData d = data();
        CraftPlanner.Plan p = CraftPlanner.plan(d, "create:andesite_alloy", 4, Map.of("minecraft:coal", 10, "minecraft:iron_ingot", 10),
                CraftPlanner.Options.defaults());
        assertEquals(2, p.steps().get(0).crafts);
        assertEquals(500, p.fluids().get("minecraft:lava"));
        assertFalse(p.ready(), "液体要另外准备");

        CraftPlanner.Plan forced = CraftPlanner.plan(d, "minecraft:iron_ingot", 1, Map.of(),
                new CraftPlanner.Options(12, Set.of(), Map.of("minecraft:iron_ingot", "minecraft:iron_ingot_from_nuggets"), true));
        // 指定用铁粒拼：铁粒只能由铁锭拆（成环）→ 记为缺铁粒，不会无限递归
        assertEquals("minecraft:iron_nugget", forced.missing().get(0).item());
        assertEquals(9, forced.missing().get(0).count());
        assertEquals("cycle", forced.missing().get(0).reason());

        CraftPlanner.Plan vanillaOnly = CraftPlanner.plan(d, "create:andesite_alloy", 1, Map.of(),
                new CraftPlanner.Options(12, Set.of(), Map.of(), false));
        assertTrue(vanillaOnly.steps().isEmpty());
        assertEquals("cycle", vanillaOnly.missing().get(0).reason(), "有配方但都被排除");
    }

    @Test
    void queriesProduceCompactJson() {
        GameData d = data();
        JsonObject r = GameDataQueries.recipeFor(d, "minecraft:iron_ingot", 2);
        assertEquals(4, r.get("total").getAsInt());
        assertEquals(2, r.getAsJsonArray("recipes").size());
        assertTrue(r.has("truncated"));
        assertEquals("工作台", r.getAsJsonArray("recipes").get(0).getAsJsonObject().get("station").getAsString());

        JsonObject u = GameDataQueries.recipeUses(d, "minecraft:stick", 10);
        assertEquals(2, u.get("total").getAsInt());

        JsonObject s = GameDataQueries.itemSources(d, "minecraft:raw_iron", 10);
        assertTrue(s.getAsJsonObject("drops").has("方块掉落"));

        JsonObject lt = GameDataQueries.lootTable(d, "zombie", 10);
        assertEquals("minecraft:entities/zombie", lt.get("table").getAsString());
        assertTrue(lt.getAsJsonArray("drops").get(1).getAsString().startsWith("minecraft:iron_ingot ×1"), lt.toString());
        assertTrue(GameDataQueries.lootTable(d, "nope_table", 10).has("error"));

        JsonObject tag = GameDataQueries.tagMembers(d, "#minecraft:planks", 10);
        assertEquals(2, tag.get("total").getAsInt());
        assertTrue(GameDataQueries.tagMembers(d, "plank", 10).has("similar"));

        JsonObject plan = GameDataQueries.plan(d, CraftPlanner.plan(d, "minecraft:torch", 4, Map.of(), CraftPlanner.Options.defaults()), null);
        assertTrue(plan.getAsJsonArray("missing").size() >= 2, plan.toString());
    }

    // ———————————————————— 1.0.0.27 代码审查修复 ————————————————————

    static GameData dataWith(List<Recipe> rs, Map<String, LootTable> loot, String... items) {
        Map<String, ItemInfo> it = new LinkedHashMap<>();
        for (String i : items) it.put(i, new ItemInfo(i, i.substring(i.indexOf(':') + 1), null));
        return new GameData(it, rs, TAGS, loot, List.of(), 0);
    }

    @Test
    void unparsableSlotsMarkRecipePartialInsteadOfDroppingThem() {
        // 三槽配方里有一个模组自定义原料类型：读成两槽 + droppedSlots = 1，不能当成原料齐全
        Recipe r = recipe("somemod:widget", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:stick\"},"
                + "{\"item\":\"minecraft:coal\"},{\"type\":\"somemod:energy_ingredient\",\"fe\":100}],\"result\":{\"id\":\"somemod:widget\",\"count\":1}}");
        assertEquals(2, r.inputs().size());
        assertEquals(1, r.droppedSlots());
        assertTrue(r.inputsUnknown(), "有丢槽 = 原料未知");
        // 有序合成：摆法里用了 key 里没有的字母
        Recipe shaped = recipe("somemod:frame", "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"#\":{\"item\":\"minecraft:stick\"}},"
                + "\"pattern\":[\"#X#\"],\"result\":{\"id\":\"somemod:frame\",\"count\":1}}");
        assertEquals(1, shaped.droppedSlots());
        // 单槽字段（锻造的 addition）读不懂也算丢槽
        Recipe smith = recipe("somemod:smith", "{\"type\":\"minecraft:smithing_transform\",\"base\":{\"item\":\"minecraft:stick\"},"
                + "\"addition\":{\"type\":\"somemod:nbt_ingredient\",\"nbt\":\"{}\"},\"template\":[],\"result\":{\"id\":\"somemod:smith\",\"count\":1}}");
        assertEquals(1, smith.droppedSlots(), "空原料 [] 不算丢槽，自定义 addition 算");
        // 空原料、普通配方不受影响
        assertEquals(0, recipe("minecraft:torch", SHAPELESS_ALT).droppedSlots());
        assertFalse(recipe("minecraft:torch", SHAPELESS_ALT).inputsUnknown());

        GameData d = dataWith(List.of(r), Map.of(), "somemod:widget", "minecraft:stick", "minecraft:coal");
        CraftPlanner.Plan p = CraftPlanner.plan(d, "somemod:widget", 1, Map.of("minecraft:stick", 5, "minecraft:coal", 5), CraftPlanner.Options.defaults());
        assertTrue(p.steps().isEmpty(), "背包里有能读到的两样也不能说已备齐");
        assertFalse(p.ready());
        assertEquals("unknown_inputs", p.missing().get(0).reason());
        assertTrue(GameDataQueries.recipeFor(d, "somemod:widget", 5).toString().contains("1 个原料槽读不出来"));
    }

    static final String TAG_POOL = "{\"type\":\"minecraft:chest\",\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:saddle\",\"weight\":20},"
            + "{\"type\":\"minecraft:tag\",\"name\":\"minecraft:planks\",\"expand\":%s,\"weight\":10}]}]}";

    @Test
    void tagEntriesFollowVanillaExpandSemantics() {
        // expand = true：#planks 的 2 种木板各是一个权重 10 的条目，总权重 20 + 10×2 = 40
        LootTable ex = loot("minecraft:chests/t", TAG_POOL.formatted("true"));
        Drop saddle = ex.drops().get(0), planks = ex.drops().get(1);
        assertEquals(0.5, saddle.chance(), 1e-9);
        assertEquals(0.25, planks.chance(), 1e-9, "每种木板 10/40");
        assertTrue(planks.tagExpand());
        // expand = false（原版默认）：标签条目只占 10，选中就每种各给一个 → 总权重 30
        LootTable all = loot("minecraft:chests/t", TAG_POOL.formatted("false"));
        assertEquals(20.0 / 30, all.drops().get(0).chance(), 1e-9);
        assertEquals(10.0 / 30, all.drops().get(1).chance(), 1e-9);
        assertFalse(all.drops().get(1).tagExpand());
        // 每个成员物品都能反查到这个来源，概率就是 chance；文字说明区分"各自抽取"和"每种各给一个"
        GameData d = dataWith(List.of(), Map.of(ex.id(), ex), "minecraft:oak_planks", "minecraft:saddle");
        assertEquals(0.25, d.sources.get("minecraft:spruce_planks").get(0).chance(), 1e-9);
        String lt = GameDataQueries.lootTable(d, "minecraft:chests/t", 10).getAsJsonArray("drops").get(1).getAsString();
        assertTrue(lt.contains("每种各自抽取") && lt.contains("25%"), lt);
        GameData d2 = dataWith(List.of(), Map.of(all.id(), all), "minecraft:oak_planks", "minecraft:saddle");
        String src = GameDataQueries.itemSources(d2, "minecraft:oak_planks", 10).getAsJsonObject("drops").getAsJsonArray("宝箱/容器").get(0).getAsString();
        assertTrue(src.contains("每种各给一个") && src.contains("33%"), src);
        // 拿不到标签成员（两参数 parse）：退化为按一个条目估算，并在说明里注明
        LootTable unknown = LootJson.parse("minecraft:chests/t", JsonParser.parseString(TAG_POOL.formatted("true")).getAsJsonObject());
        assertEquals(20.0 / 30, unknown.drops().get(0).chance(), 1e-9);
        assertTrue(String.join("", unknown.drops().get(1).functions()).contains("估算"), unknown.drops().get(1).functions().toString());
        // 展开后一个成员都没有的标签：永远抽不到，也不占权重
        LootTable empty = loot("minecraft:chests/t", TAG_POOL.formatted("true").replace("minecraft:planks", "minecraft:no_such_tag"));
        assertEquals(1.0, empty.drops().get(0).chance(), 1e-9);
        assertEquals(0.0, empty.drops().get(1).chance(), 1e-9);
    }

    @Test
    void namespacedSearchFiltersModAndMatchesPath() {
        GameData d = data();
        assertEquals(List.of("create:andesite_alloy"), d.search("create:andesite", 5).stream().map(ItemInfo::id).toList(), "带命名空间的包含匹配");
        assertTrue(d.search("minecraft:andesite", 5).isEmpty(), "命名空间不对就不出来");
        assertTrue(d.search("create:", 10).stream().allMatch(i -> i.mod().equals("create")), "只给命名空间：列出这个模组的物品");
        assertFalse(d.search("create:", 10).isEmpty());
        assertEquals("minecraft:iron_ingot", d.search("minecraft:iron_in", 5).get(0).id());
        assertEquals("create:andesite_alloy", d.resolveItem("create:andesite alloy"));
        assertEquals("create:andesite_alloy", d.resolveItem("create:Andesite Alloy"), "英文名精确匹配");
        assertNull(d.resolveItem("minecraft:andesite_alloy"), "命名空间写错不乱认");
        assertEquals("minecraft:iron_ingot", d.resolveItem("minecraft:iron_ingot"));
    }

    static final String CRUSH_PROB = "{\"type\":\"create:crushing\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],"
            + "\"results\":[{\"id\":\"somemod:dust\",\"count\":2,\"chance\":0.25}]}";

    @Test
    void probabilisticRecipeUsesSameCraftCountForScoringAndExpansion() {
        // 每次期望 2×0.25 = 0.5 个：要 3 个得做 6 次（打分和展开都按 6 次算）
        Recipe crush = recipe("create:crushing/coal", CRUSH_PROB);
        GameData d = dataWith(List.of(crush), Map.of(), "somemod:dust", "minecraft:coal");
        CraftPlanner.Plan ok = CraftPlanner.plan(d, "somemod:dust", 3, Map.of("minecraft:coal", 6), CraftPlanner.Options.defaults());
        assertTrue(ok.ready(), ok.missing().toString());
        assertEquals(6, ok.steps().get(0).crafts);
        assertTrue(ok.notes().stream().anyMatch(n -> n.contains("只能概率产出")), ok.notes().toString());
        List<String> lines = GameDataQueries.planLines(d, ok, 5, 5);
        assertTrue(lines.get(1).endsWith("×约 3.0"), "概率配方按期望值显示产量，不是 6×1：" + lines);
        // 煤只有 3 个：缺的正好是 3（旧实现打分按 3 次算以为够了）
        CraftPlanner.Plan short3 = CraftPlanner.plan(d, "somemod:dust", 3, Map.of("minecraft:coal", 3), CraftPlanner.Options.defaults());
        assertEquals(List.of(new CraftPlanner.Missing("minecraft:coal", 3, "no_recipe")), short3.missing());
        // 浮点：0.3 的期望不能因为 3/0.3 = 10.000000000000002 多算一次
        Recipe p3 = recipe("x:p3", "{\"type\":\"x:m\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],\"results\":[{\"id\":\"x:y\",\"count\":1,\"chance\":0.3}]}");
        GameData d3 = dataWith(List.of(p3), Map.of(), "x:y", "minecraft:coal");
        assertEquals(10, CraftPlanner.plan(d3, "x:y", 3, Map.of("minecraft:coal", 64), CraftPlanner.Options.defaults()).steps().get(0).crafts);
    }

    @Test
    void tiedScoresPreferCertainOutputAndAreOrderIndependent() {
        Recipe prob = recipe("aaa:prob", "{\"type\":\"create:milling\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],"
                + "\"results\":[{\"id\":\"somemod:dust\",\"count\":2,\"chance\":0.5}]}");
        Recipe sure = recipe("zzz:sure", "{\"type\":\"create:milling\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],"
                + "\"results\":[{\"id\":\"somemod:dust\",\"count\":1}]}");
        for (List<Recipe> order : List.of(List.of(prob, sure), List.of(sure, prob))) {
            GameData d = dataWith(order, Map.of(), "somemod:dust", "minecraft:coal");
            CraftPlanner.Plan p = CraftPlanner.plan(d, "somemod:dust", 2, Map.of("minecraft:coal", 2), CraftPlanner.Options.defaults());
            assertEquals("zzz:sure", p.steps().get(0).recipe.id(), "其它都同分时确定产出优先（不按 ID 先后）");
        }
        Recipe a = recipe("b:twin", "{\"type\":\"create:milling\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],\"results\":[{\"id\":\"somemod:dust\",\"count\":1}]}");
        Recipe b = recipe("a:twin", "{\"type\":\"create:milling\",\"ingredients\":[{\"item\":\"minecraft:coal\"}],\"results\":[{\"id\":\"somemod:dust\",\"count\":1}]}");
        for (List<Recipe> order : List.of(List.of(a, b), List.of(b, a))) {
            GameData d = dataWith(order, Map.of(), "somemod:dust", "minecraft:coal");
            assertEquals("a:twin", CraftPlanner.plan(d, "somemod:dust", 1, Map.of(), CraftPlanner.Options.defaults()).steps().get(0).recipe.id(),
                    "完全同分按配方 ID，和登记顺序无关");
        }
    }

    @Test
    void hugeQuantitiesSaturateInsteadOfOverflowing() {
        // 三个中间件各要 100 万个 Y：3000 个目标 → 每个中间件要 3e9 个 Y，合计远超 int
        String mid = "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"ingredient\":{\"item\":\"x:y\"},\"count\":1000000}],"
                + "\"result\":{\"id\":\"x:%s\",\"count\":1}}";
        Recipe t = recipe("x:t", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"x:a\"},{\"item\":\"x:b\"},{\"item\":\"x:c\"}],"
                + "\"result\":{\"id\":\"x:t\",\"count\":1}}");
        // 概率极低的配方：要 1 万个，期望 1e-6/次 → 1e10 次
        Recipe rare = recipe("x:rare", "{\"type\":\"x:sifting\",\"ingredients\":[{\"item\":\"x:y\"}],\"results\":[{\"id\":\"x:gem\",\"count\":1,\"chance\":0.000001}]}");
        GameData d = dataWith(List.of(t, recipe("x:a", mid.formatted("a")), recipe("x:b", mid.formatted("b")), recipe("x:c", mid.formatted("c")), rare),
                Map.of(), "x:t", "x:a", "x:b", "x:c", "x:y", "x:gem");
        CraftPlanner.Plan p = CraftPlanner.plan(d, "x:t", 3000, Map.of(), CraftPlanner.Options.defaults());
        CraftPlanner.Missing y = p.missing().get(0);
        assertEquals("x:y", y.item());
        assertTrue(y.count() > 0 && y.count() <= Integer.MAX_VALUE / 2, "封顶而不是溢出成负数：" + y.count());
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("封顶")), p.notes().toString());
        CraftPlanner.Plan g = CraftPlanner.plan(d, "x:gem", 10000, Map.of(), CraftPlanner.Options.defaults());
        assertTrue(g.steps().get(0).crafts > 0 && g.missing().get(0).count() > 0, g.steps().get(0).crafts + " / " + g.missing());
        assertTrue(g.notes().stream().anyMatch(n -> n.contains("封顶")));
        assertFalse(GameDataQueries.planLines(d, g, 5, 5).toString().contains("×-"), "显示里不出现负数");
    }

    @Test
    void twoUncomputableConditionsStayUnknownAndCompositesCombine() {
        // table_bonus 没有档位（-1）× 二项分布的 random_chance（-1）：旧实现 (-1)×(-1) = 1 显示成 100%
        LootTable t = loot("minecraft:blocks/x", "{\"pools\":[{\"rolls\":1,\"conditions\":[{\"condition\":\"minecraft:table_bonus\",\"enchantment\":\"minecraft:fortune\"},"
                + "{\"condition\":\"minecraft:random_chance\",\"chance\":{\"type\":\"minecraft:binomial\",\"n\":3,\"p\":0.5}}],"
                + "\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\"}]}]}");
        Drop x = t.drops().get(0);
        assertEquals(-1, x.chance(), 1e-9, "算不出就是算不出");
        assertTrue(x.conditions().contains("时运可提高概率"), "说明照样收集");
        String shown = GameDataQueries.lootTable(dataWith(List.of(), Map.of(t.id(), t)), "minecraft:blocks/x", 5).getAsJsonArray("drops").get(0).getAsString();
        assertFalse(shown.contains("每次"), "不显示成 100% 或任何概率：" + shown);
        // 组合条件：且 = 相乘，或 = 1 - Π(1-p)
        String comp = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\",\"conditions\":[{\"condition\":\"minecraft:%s\","
                + "\"terms\":[{\"condition\":\"minecraft:random_chance\",\"chance\":0.5},{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}]}]}]}]}";
        assertEquals(0.25, loot("minecraft:blocks/a", comp.formatted("all_of")).drops().get(0).chance(), 1e-9);
        assertEquals(0.75, loot("minecraft:blocks/b", comp.formatted("any_of")).drops().get(0).chance(), 1e-9);
    }

    @Test
    void inlineSubTableInheritsOuterChanceConditionsAndCount() {
        // 外层：普通条目权重 3 + 内联子表权重 1（带玩家击杀 + 50% 条件、set_count 2）；子表：B、C 各一半，抽 2 次
        LootTable t = loot("minecraft:entities/x", "{\"type\":\"minecraft:entity\",\"pools\":[{\"rolls\":1,\"entries\":["
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:bone\",\"weight\":3},"
                + "{\"type\":\"minecraft:loot_table\",\"weight\":1,\"conditions\":[{\"condition\":\"minecraft:killed_by_player\"},"
                + "{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}],\"functions\":[{\"function\":\"minecraft:set_count\",\"count\":2}],"
                + "\"value\":{\"pools\":[{\"rolls\":2,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\"},"
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:coal\"}]}]}}]}]}");
        Drop stick = t.drops().stream().filter(x -> x.item().equals("minecraft:stick")).findFirst().orElseThrow();
        assertEquals(1.0 / 4 * 0.5 * 0.5, stick.chance(), 1e-9, "外层 1/4 × 50% × 子表 1/2");
        assertTrue(stick.conditions().contains("需要玩家击杀"), stick.conditions().toString());
        assertEquals("2", stick.count(), "外层 set_count 覆盖子表数量");
        assertEquals("2", stick.rolls());
        assertEquals("entity", stick.kind(), "沿用外层表的类别");
    }

    @Test
    void modQuirksAreScopedBySerializerAndNeoforgeCompositesAreExact() {
        // block/count、顶层 tag 只对登记过的序列化器生效
        Recipe other = recipe("othermod:thing", "{\"type\":\"othermod:press\",\"block\":\"minecraft:stone\",\"count\":4,\"tag\":\"minecraft:planks\"}");
        assertTrue(other.outputs().isEmpty(), "别的模组的 block 字段不是产物");
        assertTrue(other.inputs().isEmpty(), "别的模组的顶层 tag 不是原料");
        // extra_inputs 只对 JustEnoughBreeding 跳过；别的模组里它可能是真原料
        Recipe extraIn = recipe("othermod:alloy", "{\"type\":\"othermod:alloying\",\"ingredient\":{\"item\":\"minecraft:coal\"},"
                + "\"extra_inputs\":[{\"item\":\"minecraft:stick\"}],\"result\":{\"id\":\"othermod:alloy\",\"count\":1}}");
        assertEquals(2, extraIn.inputs().size(), extraIn.inputs().toString());
        // neoforge:intersection = 交集；neoforge:difference = base 去掉 subtracted
        Recipe inter = recipe("x:i", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"type\":\"neoforge:intersection\",\"children\":["
                + "{\"tag\":\"minecraft:logs\"},{\"tag\":\"minecraft:oak_logs\"}]}],\"result\":{\"id\":\"x:i\",\"count\":1}}");
        assertEquals(List.of("minecraft:oak_log"), inter.inputs().get(0).options());
        Recipe comp = recipe("x:c", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"type\":\"neoforge:compound\",\"children\":["
                + "{\"tag\":\"minecraft:oak_logs\"},{\"item\":\"minecraft:stick\"}]}],\"result\":{\"id\":\"x:c\",\"count\":1}}");
        assertEquals(List.of("minecraft:oak_log", "minecraft:stick"), comp.inputs().get(0).options(), "compound 仍是并集");
        Recipe diff = recipe("x:d", "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"type\":\"neoforge:difference\","
                + "\"base\":{\"tag\":\"minecraft:planks\"},\"subtracted\":{\"item\":\"minecraft:spruce_planks\"}}],\"result\":{\"id\":\"x:d\",\"count\":1}}");
        assertEquals(List.of("minecraft:oak_planks"), diff.inputs().get(0).options());
    }

    @Test
    void snapshotsAndPlansAreImmutable() {
        List<String> logs = new ArrayList<>(List.of("minecraft:oak_log"));
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("minecraft:logs", logs);
        GameData d = new GameData(Map.of(), List.of(recipe("minecraft:torch", SHAPELESS_ALT)), tags, Map.of(), List.of(), 0);
        logs.add("minecraft:birch_log");
        assertEquals(List.of("minecraft:oak_log"), d.tags.get("minecraft:logs"), "快照的标签值是拷贝");
        assertThrows(UnsupportedOperationException.class, () -> d.tags.get("minecraft:logs").add("x"));
        Recipe r = d.recipes.get(0);
        assertThrows(UnsupportedOperationException.class, () -> r.inputs().add(r.inputs().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> r.outputs().clear());
        CraftPlanner.Plan p = CraftPlanner.plan(data(), "create:andesite_alloy", 4, Map.of("minecraft:coal", 10, "minecraft:iron_ingot", 10),
                CraftPlanner.Options.defaults());
        assertThrows(UnsupportedOperationException.class, () -> p.fluids().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.notes().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> p.steps().clear());
        Drop x = loot("minecraft:blocks/iron_ore", IRON_ORE_LOOT).drops().get(0);
        assertThrows(UnsupportedOperationException.class, () -> x.conditions().add("x"));
    }

    // ———————————————————— 1.0.0.34 资料库精度：子表权重、组合条目权重、药水内容 ————————————————————

    static final String FISHING = "{\"type\":\"minecraft:fishing\",\"pools\":[{\"rolls\":1,\"entries\":["
            + "{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:gameplay/fishing/junk\",\"weight\":10,\"quality\":-2},"
            + "{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:gameplay/fishing/treasure\",\"weight\":5,\"quality\":2,"
            + "\"conditions\":[{\"condition\":\"minecraft:entity_properties\",\"entity\":\"this\",\"predicate\":{\"type_specific\":{\"type\":\"minecraft:fishing_hook\",\"in_open_water\":true}}}]},"
            + "{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:gameplay/fishing/fish\",\"weight\":85,\"quality\":-1}]}]}";
    static final String FISH = "{\"type\":\"minecraft:fishing\",\"pools\":[{\"rolls\":1,\"entries\":["
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:cod\",\"weight\":60},{\"type\":\"minecraft:item\",\"name\":\"minecraft:salmon\",\"weight\":25},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:tropical_fish\",\"weight\":2},{\"type\":\"minecraft:item\",\"name\":\"minecraft:pufferfish\",\"weight\":13}]}]}";
    static final String JUNK = "{\"type\":\"minecraft:fishing\",\"pools\":[{\"rolls\":1,\"entries\":["
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:lily_pad\",\"weight\":17},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:potion\",\"weight\":10,\"functions\":[{\"function\":\"minecraft:set_potion\",\"id\":\"minecraft:water\"}]},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:bowl\",\"weight\":13}]}]}";
    static final String TREASURE = "{\"type\":\"minecraft:fishing\",\"pools\":[{\"rolls\":1,\"entries\":["
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:name_tag\"},{\"type\":\"minecraft:item\",\"name\":\"minecraft:saddle\"},"
            + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:nautilus_shell\"},{\"type\":\"minecraft:item\",\"name\":\"minecraft:book\"}]}]}";

    static Map<String, JsonObject> lootJson(String... idAndJson) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndJson.length; i += 2) m.put(idAndJson[i], JsonParser.parseString(idAndJson[i + 1]).getAsJsonObject());
        return m;
    }

    static Map<String, LootTable> parseAll(Map<String, JsonObject> js) {
        Map<String, LootTable> out = new LinkedHashMap<>();
        js.forEach((id, j) -> out.put(id, LootJson.parse(id, j, t -> TAGS.getOrDefault(t, List.of()), js::get)));
        return out;
    }

    static Drop find(LootTable t, String item) {
        return t.drops().stream().filter(x -> x.item().equals(item)).findFirst().orElseThrow(() -> new AssertionError(item + " 不在 " + t.drops()));
    }

    @Test
    void fishingSubTablesMultiplyTheReferencingEntryChance() {
        Map<String, JsonObject> js = lootJson("minecraft:gameplay/fishing", FISHING, "minecraft:gameplay/fishing/fish", FISH,
                "minecraft:gameplay/fishing/junk", JUNK, "minecraft:gameplay/fishing/treasure", TREASURE);
        Map<String, LootTable> loot = parseAll(js);
        LootTable fishing = loot.get("minecraft:gameplay/fishing");
        assertEquals(List.of("minecraft:gameplay/fishing/junk", "minecraft:gameplay/fishing/treasure", "minecraft:gameplay/fishing/fish"), fishing.refs());
        // 鳕鱼：父池里鱼子表 85/100（幸运 0）× 子表里 60/100
        Drop cod = find(fishing, "minecraft:cod");
        assertEquals(0.85 * 0.60, cod.chance(), 1e-9);
        assertEquals("minecraft:gameplay/fishing/fish", cod.via());
        assertEquals("fishing", cod.kind());
        assertEquals("minecraft:gameplay/fishing", cod.table(), "经子表的条目记在父表名下");
        assertTrue(cod.functions().stream().anyMatch(f -> f.startsWith("按幸运 0 计算")), "父池有 quality：注明按幸运 0 计算：" + cod.functions());
        assertTrue(cod.functions().stream().anyMatch(f -> f.contains("也在场")), "宝藏条目带说明类条件：注明按它在场计算：" + cod.functions());
        // 宝藏：带"开阔水域"条件的引用条目 = 5 /（5 + 其它无条件的 95）× 子表 1/4，条件沿用下来
        Drop tag = find(fishing, "minecraft:name_tag");
        assertEquals(0.05 / 4, tag.chance(), 1e-9);
        assertTrue(tag.conditions().contains("实体条件"), tag.conditions().toString());
        // 垃圾里的水瓶：set_potion 写进 potion
        Drop water = find(fishing, "minecraft:potion");
        assertEquals(0.10 * 10 / 40, water.chance(), 1e-9);
        assertEquals("minecraft:water", water.potion());
        // 子表自己的条目不变（子表内概率）
        assertEquals(0.60, find(loot.get("minecraft:gameplay/fishing/fish"), "minecraft:cod").chance(), 1e-9);
        assertNull(find(loot.get("minecraft:gameplay/fishing/fish"), "minecraft:cod").via());

        GameData d = dataWith(List.of(), loot, "minecraft:cod", "minecraft:potion", "minecraft:name_tag");
        assertEquals(List.of("minecraft:gameplay/fishing"), d.referencedBy.get("minecraft:gameplay/fishing/fish"));
        JsonObject src = GameDataQueries.itemSources(d, "minecraft:cod", 10);
        var lines = src.getAsJsonObject("drops").getAsJsonArray("钓鱼");
        String first = lines.get(0).getAsString(), second = lines.get(1).getAsString();
        assertTrue(first.startsWith("minecraft:gameplay/fishing（经子表 minecraft:gameplay/fishing/fish）") && first.contains("51%"), first);
        assertTrue(second.contains("这是子表") && second.contains("60%"), "子表自己的条目排后并注明：" + second);
        assertEquals(List.of("钓鱼（51%）"), GameDataQueries.topSources(d, "minecraft:cod", 1), "给玩家看的来源报实际概率，不是子表内的 60%");
        JsonObject lt = GameDataQueries.lootTable(d, "gameplay/fishing", 30);
        assertTrue(lt.toString().contains("minecraft:cod ×1，每次 51%，经子表 minecraft:gameplay/fishing/fish"), lt.toString());
        assertTrue(GameDataQueries.lootTable(d, "gameplay/fishing/fish", 30).has("referenced_by"));
        // 带药水的掉落：按物品 ID 能查到并标出药水，按变体键只看这一种
        String potionLine = GameDataQueries.itemSources(d, "minecraft:potion", 10).getAsJsonObject("drops").getAsJsonArray("钓鱼").get(0).getAsString();
        assertTrue(potionLine.contains("（药水 minecraft:water）"), potionLine);
        assertEquals(List.of("minecraft:water"), d.potionVariants.get("minecraft:potion"));
        assertThrows(UnsupportedOperationException.class, () -> d.potionVariants.get("minecraft:potion").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> d.referencedBy.get("minecraft:gameplay/fishing/fish").clear());
        assertEquals(2, d.sources.get(GameData.variant("minecraft:potion", "minecraft:water")).size(), "父表经子表的一条 + 子表自己的一条");
    }

    @Test
    void sameSubTableReferencedTwiceIsListedSeparately() {
        // 两个池各引用一次鱼子表（像守卫者掉鱼）：分别列出，各自乘自己的概率
        String guardian = "{\"type\":\"minecraft:entity\",\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:gameplay/fishing/fish\"}]},"
                + "{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:gameplay/fishing/fish\",\"weight\":1},{\"type\":\"minecraft:empty\",\"weight\":3}]}]}";
        LootTable g = parseAll(lootJson("minecraft:entities/guardian", guardian, "minecraft:gameplay/fishing/fish", FISH)).get("minecraft:entities/guardian");
        List<Drop> cods = g.drops().stream().filter(x -> x.item().equals("minecraft:cod")).toList();
        assertEquals(2, cods.size());
        assertEquals(0.60, cods.get(0).chance(), 1e-9);
        assertEquals(0.25 * 0.60, cods.get(1).chance(), 1e-9);
        assertEquals(List.of("minecraft:gameplay/fishing/fish"), g.refs(), "refs 去重");
    }

    @Test
    void subTableCyclesDepthAndMissingTablesAreBounded() {
        String ref = "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\"},"
                + "{\"type\":\"minecraft:loot_table\",\"value\":\"%s\"}]}]}";
        // A → B → A：B 的物品算进 A，回到 A 时停下并记一条说明，不会无限递归
        Map<String, LootTable> cyc = parseAll(lootJson("x:a", ref.formatted("x:b"), "x:b", ref.formatted("x:a")));
        LootTable a = cyc.get("x:a");
        assertEquals(2, a.drops().size(), a.drops().toString());
        assertEquals("x:b", a.drops().get(1).via());
        assertEquals(0.25, a.drops().get(1).chance(), 1e-9, "A 池里 1/2 选中 B × B 池里 1/2");
        assertTrue(a.notes().stream().anyMatch(n -> n.contains("成环") && n.contains("x:a → x:b → x:a")), a.notes().toString());
        assertThrows(UnsupportedOperationException.class, () -> a.notes().add("x"), "新增的 notes 也是不可变副本");
        // 自己引用自己
        LootTable self = parseAll(lootJson("x:s", ref.formatted("x:s"))).get("x:s");
        assertEquals(1, self.drops().size());
        assertTrue(self.notes().get(0).contains("成环"));
        // 一条很长的链：只展开 MAX_REF_DEPTH 层
        String[] chain = new String[20];
        for (int i = 0; i < 10; i++) {
            chain[2 * i] = "x:t" + i;
            chain[2 * i + 1] = ref.formatted("x:t" + (i + 1)).replace("minecraft:stick", "x:item" + i);
        }
        LootTable t0 = parseAll(lootJson(chain)).get("x:t0");
        assertEquals(6, t0.drops().size(), "根表 + 5 层子表各 1 个物品：" + t0.drops());
        assertTrue(t0.notes().stream().anyMatch(n -> n.contains("超过 6 层")), t0.notes().toString());
        assertEquals("x:t1 → x:t2 → x:t3 → x:t4 → x:t5", t0.drops().get(5).via());
        // 引用的表不存在：只记说明
        LootTable miss = parseAll(lootJson("x:m", ref.formatted("x:nope"))).get("x:m");
        assertEquals(1, miss.drops().size());
        assertTrue(miss.notes().get(0).contains("x:nope") && miss.notes().get(0).contains("不存在"));
        assertTrue(GameDataQueries.lootTable(dataWith(List.of(), Map.of("x:m", miss)), "x:m", 5).has("notes"));
        // 旧调用（没有 resolver）：只登记 refs，不展开、不记说明
        LootTable old = loot("x:a", ref.formatted("x:b"));
        assertEquals(1, old.drops().size());
        assertEquals(List.of("x:b"), old.refs());
        assertTrue(old.notes().isEmpty());
    }

    @Test
    void compositeChildrenWeightsJoinThePoolTotal() {
        // group：子条目全部展开成候选，各带自己的权重。A2 + B1 + C3 = 6（旧近似把 group 当成权重 1 的一条：A 2/3，B、C 各 1/3）
        LootTable g = loot("minecraft:chests/g", "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"x:a\",\"weight\":2},"
                + "{\"type\":\"minecraft:group\",\"children\":[{\"type\":\"minecraft:item\",\"name\":\"x:b\",\"weight\":1},"
                + "{\"type\":\"minecraft:item\",\"name\":\"x:c\",\"weight\":3}]}]}]}");
        assertEquals(2.0 / 6, find(g, "x:a").chance(), 1e-9);
        assertEquals(1.0 / 6, find(g, "x:b").chance(), 1e-9);
        assertEquals(3.0 / 6, find(g, "x:c").chance(), 1e-9);
        assertFalse(find(g, "x:b").alternative());
        // alternatives：只取第一个展开成功的子条目。玩家击杀时候选 = A2 + B3 → B 3/5；否则 = A2 + C1 → C 1/3、A 2/3
        LootTable alt = loot("minecraft:entities/alt", "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"x:a\",\"weight\":2},"
                + "{\"type\":\"minecraft:alternatives\",\"children\":[{\"type\":\"minecraft:item\",\"name\":\"x:b\",\"weight\":3,"
                + "\"conditions\":[{\"condition\":\"minecraft:killed_by_player\"}]},{\"type\":\"minecraft:item\",\"name\":\"x:c\",\"weight\":1},"
                + "{\"type\":\"minecraft:item\",\"name\":\"x:never\"}]}]}]}");
        assertEquals(3.0 / 5, find(alt, "x:b").chance(), 1e-9);
        assertEquals(1.0 / 3, find(alt, "x:c").chance(), 1e-9);
        assertEquals(2.0 / 3, find(alt, "x:a").chance(), 1e-9);
        assertTrue(find(alt, "x:b").alternative() && find(alt, "x:c").alternative());
        assertTrue(alt.drops().stream().noneMatch(x -> x.item().equals("x:never")), "排在必定成功的子条目后面的永远轮不到：" + alt.drops());
        // sequence：A 有 50% 随机条件，B 只有 A 成功时才展开（继承 A 的概率），两者同时在场时各 1/2
        LootTable seq = loot("minecraft:chests/s", "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:sequence\",\"children\":["
                + "{\"type\":\"minecraft:item\",\"name\":\"x:a\",\"conditions\":[{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}]},"
                + "{\"type\":\"minecraft:item\",\"name\":\"x:b\"}]}]}]}");
        assertEquals(0.25, find(seq, "x:a").chance(), 1e-9);
        assertEquals(0.25, find(seq, "x:b").chance(), 1e-9);
        // 组合条目自己的条件照样作用到子条目上
        LootTable cg = loot("minecraft:chests/cg", "{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"x:a\",\"weight\":3},"
                + "{\"type\":\"minecraft:group\",\"conditions\":[{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}],"
                + "\"children\":[{\"type\":\"minecraft:item\",\"name\":\"x:b\",\"weight\":1}]}]}]}");
        assertEquals(0.5 * 1.0 / 4, find(cg, "x:b").chance(), 1e-9);
        assertEquals(0.5 * 3 / 4 + 0.5 * 1, find(cg, "x:a").chance(), 1e-9, "池里唯一的条件条目：别人按它在场 50% / 不在场 50% 加权");
        assertFalse(find(cg, "x:a").functions().stream().anyMatch(f -> f.contains("也在场")), "纯随机条件是精确加权，不用注明");
    }

    static final String TIPPED_DATA = "{\"type\":\"minecraft:crafting_shaped\",\"key\":{\"A\":{\"item\":\"minecraft:arrow\"},"
            + "\"P\":{\"type\":\"neoforge:components\",\"items\":\"minecraft:lingering_potion\",\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:poison\"}}}},"
            + "\"pattern\":[\"AAA\",\"APA\",\"AAA\"],\"result\":{\"count\":8,\"id\":\"minecraft:tipped_arrow\","
            + "\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:poison\"}}}}";

    @Test
    void potionContentsAreParsedFromResultsIngredientsAndLootFunctions() {
        Recipe r = recipe("somemod:poison_arrows", TIPPED_DATA);
        assertEquals("minecraft:poison", r.outputs().get(0).potion());
        assertEquals("minecraft:tipped_arrow（药水 minecraft:poison）×8", r.outputs().get(0).display());
        var p = r.inputs().stream().filter(i -> i.options().contains("minecraft:lingering_potion")).findFirst().orElseThrow();
        assertEquals("minecraft:poison", p.potion());
        assertEquals("minecraft:lingering_potion（药水 minecraft:poison）×1", p.display());
        assertEquals("minecraft:lingering_potion（药水 minecraft:poison）", r.key().get("P"), "摆法的 key 也标出药水");
        // 自定义效果、只有药水 ID 的简写
        Recipe custom = recipe("somemod:custom", "{\"type\":\"somemod:infuse\",\"ingredient\":{\"item\":\"minecraft:glass_bottle\"},"
                + "\"result\":{\"id\":\"minecraft:potion\",\"count\":1,\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:water\","
                + "\"custom_color\":123,\"custom_effects\":[{\"id\":\"minecraft:speed\",\"amplifier\":1,\"duration\":600},{\"id\":\"minecraft:instant_health\"}]}}}}");
        assertEquals("minecraft:water + 自定义效果 minecraft:speed 2级 30秒、minecraft:instant_health", custom.outputs().get(0).potion());
        Recipe shorthand = recipe("somemod:short", "{\"type\":\"somemod:infuse\",\"ingredient\":{\"item\":\"minecraft:glass_bottle\"},"
                + "\"result\":{\"id\":\"minecraft:splash_potion\",\"components\":{\"potion_contents\":\"long_swiftness\"}}}");
        assertEquals("minecraft:long_swiftness", shorthand.outputs().get(0).potion());
        Recipe colorOnly = recipe("somemod:color", "{\"type\":\"somemod:infuse\",\"ingredient\":{\"item\":\"minecraft:glass_bottle\"},"
                + "\"result\":{\"id\":\"minecraft:potion\",\"components\":{\"minecraft:potion_contents\":{\"custom_color\":5}}}}");
        assertNull(colorOnly.outputs().get(0).potion(), "只有颜色不算药水");
        assertNull(recipe("minecraft:torch", SHAPELESS_ALT).outputs().get(0).potion());

        // 战利品：set_potion / set_components，带条件的只写说明
        LootTable t = loot("minecraft:chests/p", "{\"pools\":[{\"rolls\":1,\"entries\":["
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:splash_potion\",\"functions\":[{\"function\":\"minecraft:set_components\","
                + "\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:healing\"}}}]},"
                + "{\"type\":\"minecraft:item\",\"name\":\"minecraft:potion\",\"functions\":[{\"function\":\"minecraft:set_potion\",\"id\":\"minecraft:luck\","
                + "\"conditions\":[{\"condition\":\"minecraft:killed_by_player\"}]}]}]}]}");
        assertEquals("minecraft:healing", t.drops().get(0).potion());
        assertNull(t.drops().get(1).potion());
        assertTrue(t.drops().get(1).functions().contains("药水 minecraft:luck（需要玩家击杀）"), t.drops().get(1).functions().toString());
        GameData d = dataWith(List.of(), Map.of(t.id(), t), "minecraft:splash_potion");
        String line = GameDataQueries.lootTable(d, "minecraft:chests/p", 5).getAsJsonArray("drops").get(0).getAsString();
        assertTrue(line.startsWith("minecraft:splash_potion（药水 minecraft:healing） ×1"), line);
    }

    /** 合成链路 JSON 里"还缺"的水瓶那一项的 get_from。 */
    static String waterGetFrom(GameData d, CraftPlanner.Plan plan) {
        for (var m : GameDataQueries.plan(d, plan, null).getAsJsonArray("missing")) {
            JsonObject x = m.getAsJsonObject();
            if (x.get("item").getAsString().equals(GameData.variant("minecraft:potion", "minecraft:water"))) return String.valueOf(x.get("get_from"));
        }
        return "";
    }

    static Recipe brew(String inItem, String inPotion, String material, String outItem, String outPotion) {
        return RecipeJson.brewing(inItem, inPotion, new GameData.Ingredient(List.of(material), null, 1, false), outItem, outPotion);
    }

    @Test
    void potionVariantsAreDistinguishedInQueriesAndPlans() {
        List<Recipe> rs = new ArrayList<>(RecipeJson.expandSpecial("minecraft:tipped_arrow", "minecraft:crafting_special_tippedarrow", "minecraft:crafting",
                List.of("minecraft:poison", "minecraft:long_swiftness")));
        assertEquals(2, rs.size());
        assertEquals("minecraft:tipped_arrow[minecraft:poison]", rs.get(0).id());
        assertEquals("工作台", rs.get(0).station());
        assertTrue(RecipeJson.expandSpecial("minecraft:firework_rocket", "minecraft:crafting_special_firework_rocket", "minecraft:crafting",
                List.of("minecraft:poison")).isEmpty(), "别的特殊配方不展开");
        // 酿造链：粗制 → 剧毒 → 喷溅剧毒 → 滞留剧毒；再放一条迅捷的做干扰
        Recipe poison = brew("minecraft:potion", "minecraft:awkward", "minecraft:spider_eye", "minecraft:potion", "minecraft:poison");
        assertEquals("brewing:potion/poison/from_potion/awkward/spider_eye", poison.id());
        assertEquals("酿造台", poison.station());
        assertTrue(RecipeJson.isVanillaStation(poison.category()));
        assertEquals(3, poison.surely(GameData.variant("minecraft:potion", "minecraft:poison")));
        assertEquals(0, poison.surely(GameData.variant("minecraft:potion", "minecraft:healing")), "药水不一致不算");
        assertEquals(3, poison.surely("minecraft:potion"), "没指定药水时任何药水都算");
        rs.add(poison);
        rs.add(brew("minecraft:potion", "minecraft:water", "minecraft:nether_wart", "minecraft:potion", "minecraft:awkward"));
        rs.add(brew("minecraft:potion", "minecraft:poison", "minecraft:gunpowder", "minecraft:splash_potion", "minecraft:poison"));
        rs.add(brew("minecraft:splash_potion", "minecraft:poison", "minecraft:dragon_breath", "minecraft:lingering_potion", "minecraft:poison"));
        rs.add(brew("minecraft:splash_potion", "minecraft:long_swiftness", "minecraft:dragon_breath", "minecraft:lingering_potion", "minecraft:long_swiftness"));
        rs.add(recipe("somemod:poison_arrows", TIPPED_DATA));
        GameData d = dataWith(rs, Map.of(), "minecraft:tipped_arrow", "minecraft:arrow", "minecraft:lingering_potion", "minecraft:splash_potion",
                "minecraft:potion", "minecraft:spider_eye", "minecraft:gunpowder", "minecraft:dragon_breath");

        // 按物品 ID：全部药水都列出并标明，附药水清单和提示
        JsonObject all = GameDataQueries.recipeFor(d, "minecraft:tipped_arrow", 10);
        assertEquals(3, all.get("total").getAsInt());
        assertEquals(List.of("minecraft:long_swiftness", "minecraft:poison"), d.potionVariants.get("minecraft:tipped_arrow"));
        assertTrue(all.has("potion_hint") && all.getAsJsonArray("potions").size() == 2, all.toString());
        assertTrue(all.toString().contains("minecraft:tipped_arrow（药水 minecraft:long_swiftness）×8"), all.toString());
        // 按药水：只看这一种（模糊写法也能认）
        String key = d.withPotion("minecraft:tipped_arrow", "poison");
        assertEquals("minecraft:tipped_arrow[potion=minecraft:poison]", key);
        assertEquals(GameData.variant("minecraft:tipped_arrow", "minecraft:long_swiftness"), d.withPotion("minecraft:tipped_arrow", "swift"));
        JsonObject one = GameDataQueries.recipeFor(d, key, 10);
        assertEquals(2, one.get("total").getAsInt(), "展开的原版药箭 + 模组的数据配方");
        assertFalse(one.has("potion_hint"));
        assertTrue(one.get("name").getAsString().contains("药水 minecraft:poison"), one.toString());
        JsonObject none = GameDataQueries.recipeFor(d, d.withPotion("minecraft:tipped_arrow", "minecraft:healing"), 10);
        assertEquals(0, none.get("total").getAsInt());
        assertTrue(none.get("potion_hint").getAsString().contains("没有找到这种药水"), none.toString());
        JsonObject search = GameDataQueries.itemSearch(d, "tipped_arrow", 5);
        assertEquals(2, search.getAsJsonArray("items").get(0).getAsJsonObject().get("potion_variants").getAsInt(), search.toString());
        assertTrue(search.has("potion_hint"));
        assertTrue(GameDataQueries.recipeUses(d, "minecraft:lingering_potion", 10).get("total").getAsInt() >= 3, "按物品 ID 反查带药水的原料");

        // 合成链路：要剧毒药箭 → 顺着药水一路找到粗制药水，不会选成迅捷的滞留药水
        CraftPlanner.Plan plan = CraftPlanner.plan(d, key, 8, Map.of("minecraft:arrow", 64), CraftPlanner.Options.defaults());
        List<String> ids = plan.steps().stream().map(s -> s.recipe.id()).toList();
        assertTrue(ids.contains("brewing:lingering_potion/poison/from_splash_potion/poison/dragon_breath"), ids.toString());
        assertTrue(ids.contains("brewing:potion/poison/from_potion/awkward/spider_eye"), ids.toString());
        assertTrue(ids.stream().noneMatch(i -> i.contains("long_swiftness")), ids.toString());
        Map<String, Integer> miss = new LinkedHashMap<>();
        plan.missing().forEach(m -> miss.put(m.item(), m.count()));
        assertEquals(3, miss.get(GameData.variant("minecraft:potion", "minecraft:water")), "酿造按满 3 瓶记，一路追到水瓶：" + miss);
        assertEquals(1, miss.get("minecraft:spider_eye"));
        assertEquals(1, miss.get("minecraft:nether_wart"));
        String waterMiss = waterGetFrom(d, plan);
        assertTrue(waterMiss.contains("装水"), "水瓶给出装水的说明：" + waterMiss);
        JsonObject pj = GameDataQueries.plan(d, plan, null);
        assertTrue(pj.get("target").getAsString().contains("药水 minecraft:poison"), pj.toString());
        assertTrue(pj.toString().contains("酿造台 做 1 次"), pj.toString());
        assertTrue(pj.has("potion_note"));
        assertTrue(GameDataQueries.planLines(d, plan, 10, 5).toString().contains("lingering_potion（药水 minecraft:poison）×1"),
                GameDataQueries.planLines(d, plan, 10, 5).toString());
        // 没指定药水：任选一种并提示
        JsonObject anyPlan = GameDataQueries.plan(d, CraftPlanner.plan(d, "minecraft:tipped_arrow", 8, Map.of(), CraftPlanner.Options.defaults()), null);
        assertTrue(anyPlan.has("potion_hint"), anyPlan.toString());
    }
}
