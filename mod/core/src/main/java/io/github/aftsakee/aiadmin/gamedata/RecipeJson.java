package io.github.aftsakee.aiadmin.gamedata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.gamedata.GameData.Ingredient;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameData.Stack;
import io.github.aftsakee.aiadmin.util.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 把"配方编码成的 JSON"解析成输入/输出。平台层用每条配方自己的序列化器（RecipeSerializer.codec）把它编码成 JSON 再交给这里，
 * 所以原版和模组机器（Create 的搅拌、压制、粉碎…）走同一套解析，不需要为每个模组写适配。
 * <p>边界：
 * <ul>
 *   <li>输出字段：result / results / output / outputs（字符串 ID、{id|item, count|amount, chance}、或它们的数组）。</li>
 *   <li>数据组件：产物的 components、neoforge:components 原料的 components 只解析药水内容（minecraft:potion_contents，
 *       见 {@link ItemComponents}），写进 Stack/Ingredient 的 potion；其它组件（名字、附魔、烟花飞行时间…）不影响"是什么物品"，不解析。</li>
 *   <li>有序合成：key + pattern，数每个字母在摆法里出现几次。</li>
 *   <li>输入字段：ingredients / inputs（数组，每个元素是一个槽；元素本身是数组 = 多选一）、ingredient / input / base / template / addition，
 *       以及其它字段里"长得像原料"的对象（含 item 或 tag）。</li>
 *   <li>原料写法：{item}、{tag}、字符串、数组（多选一）、{ingredient, count}（带数量的原料）、neoforge:compound（children 取并集）、
 *       neoforge:intersection（children 取交集）、neoforge:difference（base 去掉 subtracted；subtracted 读不懂时只取 base，结果偏宽）、
 *       液体 {fluid|fluid_tag, amount}。</li>
 *   <li>原料槽解析不出（模组自定义的原料类型、key 里缺字母）时不静默丢弃：数进 {@link Parsed#droppedSlots}，
 *       配方按"原料不全"处理（{@link GameData.Recipe#inputsUnknown}，不参与合成规划），否则三槽配方读成两槽会被说成"已备齐"。
 *       空原料 []（原版表示"这个槽不放东西"）以及数字/布尔这类明显不是原料的值不算丢槽。</li>
 *   <li>个别模组的非标准写法登记在 {@link #ADAPTERS}，按配方序列化器 type（完整 ID 或命名空间）限定，只对那个模组的配方生效。</li>
 *   <li>解析不出来的配方（自定义写法）由平台层用 Recipe#getIngredients / getResultItem 兜底；两样都没有的就只记类型。</li>
 * </ul>
 */
public final class RecipeJson {
    private static final Set<String> OUTPUT_KEYS = Set.of("result", "results", "output", "outputs");
    private static final Set<String> SLOT_LIST_KEYS = Set.of("ingredients", "inputs");
    private static final Set<String> SLOT_KEYS = Set.of("ingredient", "input", "base", "template", "addition");
    /** 对所有配方都不是原料、也不往 extra 里放的字段（原版/NeoForge 的通用元数据）。模组特有的跳过字段写在 {@link #ADAPTERS}。 */
    private static final Set<String> SKIP_KEYS = Set.of("type", "group", "category", "show_notification", "neoforge:conditions",
            "conditions", "key", "pattern");
    private static final int EXTRA_MAX = 300;

    /**
     * 个别模组的非标准写法。blockOutput = 没有产物字段时把字符串 block + count 当产物；topTagInput = 顶层字符串 tag + count 是一个标签原料
     * （count 属于这个原料，不是产物数量）；skipKeys = 这个模组额外的"不是原料"字段。
     */
    private record Adapter(boolean blockOutput, boolean topTagInput, Set<String> skipKeys) {}

    private static final Adapter GENERIC = new Adapter(false, false, Set.of());

    /**
     * 适配表：键是完整的序列化器 ID（优先）或命名空间（这个模组的所有序列化器）。
     * 原因：这些写法和通用字段同名（block、tag、count、extra_inputs），对所有配方生效会误伤其它模组（比如把别的模组的 tag 字段当原料、
     * 把真正的额外原料 extra_inputs 跳过而显得更省料）。按命名空间登记的条目只影响那一个模组：同一模组的其它序列化器大概率沿用同一套写法，
     * 而按完整 ID 登记会让没见过的同模组序列化器悄悄丢原料。约束：新增条目必须注明来源模组、见过的序列化器和原因，并配单测。
     */
    private static final Map<String, Adapter> ADAPTERS = Map.of(
            // Domum Ornamentum（见过 domum_ornamentum:architects_cutter 建筑师切割机）：{"block": 产物方块, "count": 每次几个}；
            // 原料按放进去的方块材质动态决定，读不到 → 原料留空 = inputsUnknown（1.0.0.18 线上复核：88 条被当成"0 原料"配方）
            "domum_ornamentum", new Adapter(true, false, Set.of()),
            // MineColonies Trade Post（见过 mctradepost:unique_tag_shapeless）：顶层 {"tag": 标签, "count": n} 是一个标签原料
            //（1.0.0.18 线上复核：bar_nuts 的坚果种子丢失）
            "mctradepost", new Adapter(false, true, Set.of()),
            // JustEnoughBreeding（justenoughbreeding:*）：繁殖/驯服说明数据（JEI 展示用），刷怪蛋和额外说明物品不是原料。
            // 平台层已整体跳过这个命名空间，这里是直接解析时的双保险
            "justenoughbreeding", new Adapter(false, false, Set.of("spawn_eggs", "input_spawn_eggs", "extra_inputs")));

    private RecipeJson() {}

    /** droppedSlots = 解析不出、被丢掉的原料槽数（&gt; 0 时 inputs 不全）。 */
    public record Parsed(List<Ingredient> inputs, List<Stack> outputs, List<String> shape, Map<String, String> key, String extra,
                         int droppedSlots) {}

    static Adapter adapter(String type) {
        if (type == null) return GENERIC;
        Adapter a = ADAPTERS.get(type);
        int i = type.indexOf(':');
        if (a == null && i > 0) a = ADAPTERS.get(type.substring(0, i));
        return a == null ? GENERIC : a;
    }

    /** tagItems：标签 ID（不带 #）→ 成员物品 ID 列表；不认识的标签返回空列表。 */
    public static Parsed parse(JsonObject j, Function<String, List<String>> tagItems) {
        Adapter ad = adapter(Json.str(j.get("type")));
        List<Stack> outputs = new ArrayList<>();
        for (String k : OUTPUT_KEYS) if (j.has(k)) stacks(j.get(k), outputs);
        String block = Json.str(j.get("block"));
        boolean blockOutput = ad.blockOutput() && outputs.isEmpty() && block != null && block.contains(":");
        if (blockOutput) outputs.add(new Stack(block, Math.max(1, Json.intOf(j, "count", 1)), 1, false));
        boolean topTag = ad.topTagInput() && Json.str(j.get("tag")) != null;
        List<Ingredient> inputs = new ArrayList<>();
        int dropped = 0;
        List<String> shape = null;
        Map<String, String> keyDisplay = null;
        if (j.has("key") && j.get("key").isJsonObject() && j.has("pattern") && j.get("pattern").isJsonArray()) {
            shape = new ArrayList<>();
            Map<Character, Integer> counts = new LinkedHashMap<>();
            for (JsonElement row : j.getAsJsonArray("pattern")) {
                String r = Json.str(row);
                if (r == null) continue;
                shape.add(r);
                for (char c : r.toCharArray()) if (c != ' ') counts.merge(c, 1, Integer::sum);
            }
            keyDisplay = new LinkedHashMap<>();
            JsonObject key = j.getAsJsonObject("key");
            for (Map.Entry<Character, Integer> e : counts.entrySet()) {
                JsonElement v = key.get(String.valueOf(e.getKey()));
                Ingredient ing = v == null ? null : ingredient(v, e.getValue(), tagItems);
                if (ing != null) {
                    inputs.add(ing);
                    keyDisplay.put(String.valueOf(e.getKey()), (ing.tag() != null ? "#" + ing.tag() : String.join("|", ing.options()))
                            + GameData.potionNote(ing.potion()));
                } else if (v == null || structured(v)) {
                    dropped++; // 摆法里用了 key 里没有的字母，或原料写法不认识：这个字母的所有格子都缺
                }
            }
        }
        JsonObject extra = new JsonObject();
        if (topTag) {
            Ingredient ing = tag(Json.str(j.get("tag")), Math.max(1, Json.intOf(j, "count", 1)), false, tagItems);
            if (ing != null) inputs.add(ing);
        }
        for (Map.Entry<String, JsonElement> e : j.entrySet()) {
            String k = e.getKey();
            if (OUTPUT_KEYS.contains(k) || SKIP_KEYS.contains(k) || ad.skipKeys().contains(k)) continue;
            if (blockOutput && (k.equals("block") || k.equals("count"))) continue;
            if (topTag && (k.equals("tag") || k.equals("count"))) continue;
            JsonElement v = e.getValue();
            if (SLOT_LIST_KEYS.contains(k) && v.isJsonArray()) {
                for (JsonElement el : v.getAsJsonArray()) {
                    Ingredient ing = ingredient(el, 1, tagItems);
                    if (ing != null) inputs.add(ing);
                    else if (structured(el)) dropped++;
                }
            } else if (SLOT_KEYS.contains(k) || looksLikeIngredient(v)) {
                Ingredient ing = ingredient(v, 1, tagItems);
                if (ing != null) {
                    inputs.add(ing);
                } else {
                    if (structured(v)) dropped++;
                    extra.add(k, v);
                }
            } else {
                extra.add(k, v);
            }
        }
        String ex = extra.size() == 0 ? null : extra.toString();
        if (ex != null && ex.length() > EXTRA_MAX) ex = ex.substring(0, EXTRA_MAX) + "…";
        return new Parsed(merge(inputs), outputs, shape, keyDisplay, ex, dropped);
    }

    /**
     * 原料解析失败时算不算"丢了一个槽"：对象、非空数组（看得出是想写原料的结构）算；
     * 空数组 [] 是原版的空原料（这个槽不放东西），null/数字/布尔/普通字符串不像原料，都不算——否则会把能用的配方误标成原料不全。
     */
    private static boolean structured(JsonElement v) {
        return v != null && (v.isJsonObject() || (v.isJsonArray() && !v.getAsJsonArray().isEmpty()));
    }

    /** 同一种原料在无序配方里分几个槽出现时合并成一条（2 个木棍 = 木棍×2）。 */
    static List<Ingredient> merge(List<Ingredient> in) {
        Map<String, Ingredient> m = new LinkedHashMap<>();
        for (Ingredient i : in) {
            String k = i.fluid() + "|" + i.tag() + "|" + i.options() + "|" + i.potion();
            Ingredient prev = m.get(k);
            m.put(k, prev == null ? i : new Ingredient(i.options(), i.tag(), prev.count() + i.count(), i.fluid(), i.potion()));
        }
        return new ArrayList<>(m.values());
    }

    private static boolean looksLikeIngredient(JsonElement v) {
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            return o.has("item") || o.has("tag") || o.has("fluid") || o.has("fluid_tag");
        }
        if (v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
            JsonElement f = v.getAsJsonArray().get(0);
            return f.isJsonObject() && (f.getAsJsonObject().has("item") || f.getAsJsonObject().has("tag"));
        }
        return false;
    }

    /** 解析一个原料槽；mult = 这个槽出现几次（有序合成里同一字母出现的次数）。解析不了返回 null。 */
    static Ingredient ingredient(JsonElement e, int mult, Function<String, List<String>> tagItems) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            String s = Json.str(e);
            if (s == null || !s.contains(":")) return null;
            if (s.startsWith("#")) return tag(s.substring(1), mult, false, tagItems);
            return new Ingredient(List.of(s), null, mult, false);
        }
        if (e.isJsonArray()) {
            // 数组 = 多选一：合并所有选项；全是同一个标签时保留标签名
            // 药水：每个选项都要求同一种药水时保留；混着不同药水（或有的不要求）时记 null（选项按物品 ID 合并，分不出哪个选项要哪种）
            Set<String> opts = new LinkedHashSet<>();
            Set<String> potions = new LinkedHashSet<>();
            String onlyTag = null;
            int tags = 0, n = 0;
            boolean fluid = false;
            for (JsonElement el : e.getAsJsonArray()) {
                Ingredient i = ingredient(el, 1, tagItems);
                if (i == null) continue;
                n++;
                opts.addAll(i.options());
                potions.add(String.valueOf(i.potion()));
                fluid |= i.fluid();
                if (i.tag() != null) {
                    tags++;
                    onlyTag = i.tag();
                }
            }
            if (n == 0) return null;
            String potion = potions.size() == 1 && !potions.contains("null") ? potions.iterator().next() : null;
            return new Ingredient(List.copyOf(opts), n == 1 && tags == 1 ? onlyTag : null, mult, fluid, potion);
        }
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String type = Json.str(o.get("type"));
        int count = Math.max(1, Json.intOf(o, "count", Json.intOf(o, "amount", 1)));
        if (o.has("fluid") || o.has("fluid_tag") || "fluid_stack".equals(Json.path(type)) || "fluid_tag".equals(Json.path(type))) {
            String f = Json.str(o.get("fluid"));
            String ft = Json.str(o.get("fluid_tag"));
            if (ft != null) return new Ingredient(List.of("#" + ft), ft, Math.max(1, Json.intOf(o, "amount", 1)) * mult, true);
            if (f != null) return new Ingredient(List.of(f), null, Math.max(1, Json.intOf(o, "amount", 1)) * mult, true);
            return null;
        }
        if (o.has("item")) {
            String id = Json.str(o.get("item"));
            if (id == null && o.get("item").isJsonObject()) id = Json.str(o.getAsJsonObject("item").get("id"));
            return id == null ? null : new Ingredient(List.of(id), null, count * mult, false);
        }
        if (o.has("tag")) return tag(Json.str(o.get("tag")), count * mult, false, tagItems);
        if (o.has("ingredient")) {
            // 带数量的原料（NeoForge SizedIngredient、Create 等）：{ingredient: …, count: n}
            Ingredient inner = ingredient(o.get("ingredient"), 1, tagItems);
            return inner == null ? null : new Ingredient(inner.options(), inner.tag(), count * mult * inner.count(), inner.fluid(), inner.potion());
        }
        if (o.has("children")) {
            // neoforge:intersection = 同时满足所有子原料（交集）；neoforge:compound 及其它 = 满足任一子原料（并集）
            if ("neoforge:intersection".equals(type)) return intersection(o.get("children"), count * mult, tagItems);
            return ingredient(o.get("children"), count * mult, tagItems);
        }
        if (o.has("base")) {
            Ingredient base = ingredient(o.get("base"), count * mult, tagItems);
            // neoforge:difference = base 去掉 subtracted；subtracted 读不懂时只能取 base（偏宽：可能列出其实不能用的物品）
            if (base != null && o.has("subtracted")) {
                Ingredient sub = ingredient(o.get("subtracted"), 1, tagItems);
                if (sub != null) {
                    Set<String> rest = new LinkedHashSet<>(base.options());
                    rest.removeAll(new HashSet<>(sub.options()));
                    return new Ingredient(List.copyOf(rest), null, base.count(), base.fluid(), base.potion());
                }
            }
            return base;
        }
        if (o.has("items")) {
            // neoforge:components 等：{items: …, components: …, strict}。组件里只认药水内容（要哪种药水），其它组件不影响"放什么物品"
            Ingredient inner = ingredient(o.get("items"), 1, tagItems);
            String potion = ItemComponents.potion(o.get("components"));
            return inner == null ? null : new Ingredient(inner.options(), inner.tag(), count * mult, inner.fluid(), potion != null ? potion : inner.potion());
        }
        if (o.has("id")) {
            String id = Json.str(o.get("id"));
            return id == null || !id.contains(":") ? null : new Ingredient(List.of(id), null, count * mult, false);
        }
        return null;
    }

    /** 交集：保持第一个子原料里的顺序；任何一个子原料读不懂就没法求交集，按读不出（null）处理。结果不再是某个标签，tag 记 null。 */
    private static Ingredient intersection(JsonElement children, int mult, Function<String, List<String>> tagItems) {
        if (children == null || !children.isJsonArray()) return null;
        Set<String> common = null;
        boolean fluid = false;
        for (JsonElement c : children.getAsJsonArray()) {
            Ingredient i = ingredient(c, 1, tagItems);
            if (i == null) return null;
            fluid |= i.fluid();
            if (common == null) common = new LinkedHashSet<>(i.options());
            else common.retainAll(new HashSet<>(i.options()));
        }
        return common == null ? null : new Ingredient(List.copyOf(common), null, mult, fluid);
    }

    private static Ingredient tag(String t, int count, boolean fluid, Function<String, List<String>> tagItems) {
        if (t == null || t.isBlank()) return null;
        List<String> members = tagItems.apply(t);
        return new Ingredient(members == null ? List.of() : List.copyOf(members), t, count, fluid);
    }

    /** 解析产物：字符串、对象、数组。 */
    static void stacks(JsonElement e, List<Stack> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonArray()) {
            for (JsonElement el : e.getAsJsonArray()) stacks(el, out);
            return;
        }
        if (e.isJsonPrimitive()) {
            String s = Json.str(e);
            if (s != null && s.contains(":")) out.add(new Stack(s, 1, 1, false));
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        double chance = o.has("chance") ? Json.dbl(o.get("chance"), 1) : 1;
        String potion = ItemComponents.potion(o.get("components")); // ItemStack.CODEC：{id, count, components: {"minecraft:potion_contents": …}}
        if (o.has("fluid")) {
            String f = Json.str(o.get("fluid"));
            if (f == null && o.get("fluid").isJsonObject()) f = Json.str(o.getAsJsonObject("fluid").get("id"));
            if (f != null) out.add(new Stack(f, Math.max(1, Json.intOf(o, "amount", 1000)), chance, true));
            return;
        }
        String id = Json.str(o.get("id"));
        if (id == null) id = Json.str(o.get("item"));
        if (id == null && o.has("item") && o.get("item").isJsonObject()) {
            // 旧写法 {item: {id, count}, chance}
            JsonObject in = o.getAsJsonObject("item");
            id = Json.str(in.get("id"));
            String ip = ItemComponents.potion(in.get("components"));
            if (id != null) out.add(new Stack(id, Math.max(1, Json.intOf(in, "count", 1)), chance, false, ip != null ? ip : potion));
            return;
        }
        if (id == null) return;
        boolean fluid = o.has("amount") && !o.has("count");
        out.add(new Stack(id, Math.max(1, Json.intOf(o, "count", Json.intOf(o, "amount", fluid ? 1000 : 1))), chance, fluid, fluid ? null : potion));
    }

    /** 原版药箭的特殊配方序列化器（TippedArrowRecipe）。 */
    static final String TIPPED_ARROW_TYPE = "minecraft:crafting_special_tippedarrow";

    /**
     * 代码实现、但能按药水展开成普通配方的特殊配方。目前只有原版药箭（{@link #TIPPED_ARROW_TYPE}：3×3 摆 8 支箭围着 1 瓶滞留药水 →
     * 8 支同药水的药箭，产物药水 = 滞留药水的药水）。
     * <p>原因：它的编码 JSON 只有 type/category，读不到产物，recipe_for 药箭会说"没有配方"。按 potions 每种药水生成一条普通配方，
     * ID = 原配方 ID + "[药水 ID]"（如 minecraft:tipped_arrow[minecraft:poison]），原料里的滞留药水带同一种药水，合成规划能顺着往下找。
     * <p>边界：potions 由平台层提供（药水注册表里能酿出来的 + 水瓶），core 不知道有哪些药水；原配方本身（无产物的特殊配方）保留不动。
     * 其它特殊配方返回空列表。
     */
    public static List<Recipe> expandSpecial(String recipeId, String type, String category, List<String> potions) {
        if (!TIPPED_ARROW_TYPE.equals(type) || potions == null) return List.of();
        String cat = category == null ? "minecraft:crafting" : category;
        List<Recipe> out = new ArrayList<>();
        for (String p : new LinkedHashSet<>(potions)) {
            if (p == null || p.isBlank()) continue;
            Map<String, String> key = new LinkedHashMap<>();
            key.put("A", "minecraft:arrow");
            key.put("P", "minecraft:lingering_potion" + GameData.potionNote(p));
            out.add(new Recipe(recipeId + "[" + p + "]", type, cat, station(cat, type),
                    List.of(new Ingredient(List.of("minecraft:arrow"), null, 8, false),
                            new Ingredient(List.of("minecraft:lingering_potion"), null, 1, false, p)),
                    List.of(new Stack("minecraft:tipped_arrow", 8, 1, false, p)),
                    List.of("AAA", "APA", "AAA"), key, "按药水展开的特殊配方（原配方 " + recipeId + "）", 0));
        }
        return out;
    }

    /** 酿造台配方的类型/类别 ID（原版没有这个配方类型：酿造是 PotionBrewing 里的代码，这里借用作标记）。 */
    public static final String BREWING = "minecraft:brewing";
    /** 酿造台一次最多放几瓶。 */
    static final int BREW_BOTTLES = 3;

    /**
     * 一条酿造台配方。平台层从 PotionBrewing 只读枚举出来（见 neoforge 的 GameDataBrewing），这里只负责拼成普通配方：
     * 瓶子槽 3 瓶同样的 inItem（药水 inPotion）+ 材料 1 份 → 3 瓶 outItem（药水 outPotion）。
     * <p>为什么按满载 3 瓶记：材料每酿一次只耗 1 份（放 1~2 瓶也耗 1 份），按 1 瓶记会把材料（恶魂之泪、兔子脚…）多算到 3 倍；
     * 代价是只要 1 瓶时也会规划做 3 瓶（多出的进"剩余"）。燃料（烈焰粉，1 个酿 20 次）不算原料，写在 extra。
     * <p>ID 是拼出来的，稳定（同样的输入总得到同样的 ID），形如 brewing:splash_potion/healing/from_potion/healing/gunpowder；
     * 原版命名空间省略，其它命名空间的 ':' 换成 '.'；只有自定义效果的药水写 custom。
     */
    public static Recipe brewing(String inItem, String inPotion, Ingredient ingredient, String outItem, String outPotion) {
        String with = ingredient.options().isEmpty() ? "unknown" : slug(ingredient.options().get(0));
        String id = "brewing:" + slug(outItem) + "/" + potionSlug(outPotion) + "/from_" + slug(inItem) + "/" + potionSlug(inPotion) + "/" + with;
        Ingredient bottles = new Ingredient(List.of(inItem), null, BREW_BOTTLES, false, inPotion);
        Ingredient material = new Ingredient(ingredient.options(), ingredient.tag(), 1, false, ingredient.potion());
        return new Recipe(id, BREWING, BREWING, station(BREWING, BREWING), List.of(bottles, material),
                List.of(new Stack(outItem, BREW_BOTTLES, 1, false, outPotion)), null, null,
                "酿造台：一次可放 1~3 瓶，材料每次只耗 1 份，这里按满 3 瓶记；燃料烈焰粉（1 个可酿 20 次）另备", 0);
    }

    private static String slug(String id) {
        if (id == null) return "none";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id.replace(':', '.');
    }

    private static String potionSlug(String potion) {
        if (potion == null) return "none";
        return potion.contains(" ") ? "custom" : slug(potion); // 带自定义效果的说明里有空格
    }

    /** 配方类型 → 在哪做。原版给中文名；模组的直接给类型 ID（模型认识 Create 等常见模组的机器）。 */
    public static String station(String category, String type) {
        String c = category == null ? "" : category;
        return switch (c) {
            case "minecraft:crafting" -> "工作台";
            case BREWING -> "酿造台";
            case "minecraft:smelting" -> "熔炉";
            case "minecraft:blasting" -> "高炉";
            case "minecraft:smoking" -> "烟熏炉";
            case "minecraft:campfire_cooking" -> "营火";
            case "minecraft:stonecutting" -> "切石机";
            case "minecraft:smithing" -> "锻造台";
            default -> (c.isEmpty() ? type : c) + "（模组机器/工序）";
        };
    }

    /**
     * 原版工作站在 {@link #stationRank} 里的最大档（工作台…烟熏炉/营火/酿造台 = 0..4；模组机器 = 6；没有类别 = 9）。
     * 约束：stationRank 新增原版工作站时要保持 ≤ 这个值、模组机器 &gt; 这个值——CraftPlanner 的"只用原版工作站"过滤和
     * "像样配方"判断都通过 {@link #isVanillaStation} 依赖这条分界。
     */
    private static final int VANILLA_STATION_MAX_RANK = 4;

    /** 做选择时的偏好：越小越优先。工作台 &lt; 熔炉/锻造 &lt; 切石 &lt; 高炉 &lt; 烟熏/营火/酿造台 &lt; 模组机器。 */
    public static int stationRank(String category) {
        if (category == null) return 9;
        return switch (category) {
            case "minecraft:crafting" -> 0;
            case "minecraft:smelting", "minecraft:smithing" -> 1;
            case "minecraft:stonecutting" -> 2;
            case "minecraft:blasting" -> 3;
            case "minecraft:smoking", "minecraft:campfire_cooking", BREWING -> 4;
            default -> 6;
        };
    }

    /** 原版工作站（工作台、熔炉、锻造台、切石机、高炉、烟熏炉、营火、酿造台）的配方类型。 */
    public static boolean isVanillaStation(String category) {
        return stationRank(category) <= VANILLA_STATION_MAX_RANK;
    }
}
