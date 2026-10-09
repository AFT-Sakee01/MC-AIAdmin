package io.github.aftsakee.aiadmin.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner.Missing;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner.Plan;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner.Step;
import io.github.aftsakee.aiadmin.gamedata.GameData.Drop;
import io.github.aftsakee.aiadmin.gamedata.GameData.Ingredient;
import io.github.aftsakee.aiadmin.gamedata.GameData.ItemInfo;
import io.github.aftsakee.aiadmin.gamedata.GameData.LootTable;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameData.Stack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 GameData 的查询结果组织成给模型看的紧凑 JSON（工具返回值），以及给玩家看的几行文字（/goal 指令、定时提示）。
 * 只读、无副作用；所有列表都有条数上限，避免一次工具调用撑爆上下文。
 */
public final class GameDataQueries {
    private GameDataQueries() {}

    // ———————————————————— 物品 ————————————————————

    public static JsonObject itemSearch(GameData d, String query, int limit) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        boolean potions = false;
        for (ItemInfo i : d.search(query, limit)) {
            JsonObject x = new JsonObject();
            x.addProperty("id", i.id());
            x.addProperty("name", i.label());
            x.addProperty("mod", i.mod());
            x.addProperty("recipes", d.byOutput.getOrDefault(i.id(), List.of()).size());
            x.addProperty("used_in", d.byInput.getOrDefault(i.id(), List.of()).size());
            x.addProperty("drop_sources", d.sources.getOrDefault(i.id(), List.of()).size());
            int pv = d.potionVariants.getOrDefault(i.id(), List.of()).size();
            if (pv > 0) {
                x.addProperty("potion_variants", pv);
                potions = true;
            }
            arr.add(x);
        }
        o.add("items", arr);
        if (potions) o.addProperty("potion_hint", "带 potion_variants 的物品有多种药水（同一物品 ID）；recipe_for / item_sources / craft_plan 可加 potion 参数只看某一种");
        if (arr.isEmpty()) o.addProperty("note", "没找到。原版物品服务端只有英文名，请用英文名或 ID 片段（如 iron_ingot）再试。");
        return o;
    }

    // ———————————————————— 配方 ————————————————————

    public static JsonObject recipeFor(GameData d, String item, int limit) {
        JsonObject o = head(d, item);
        List<Recipe> rs = new ArrayList<>(d.byOutput.getOrDefault(item, List.of()));
        rs.sort(Comparator.comparingInt((Recipe r) -> RecipeJson.stationRank(r.category())).thenComparing(Recipe::id));
        o.addProperty("total", rs.size());
        o.add("recipes", recipes(d, rs, limit, o));
        if (rs.isEmpty()) o.addProperty("note", "没有配方能做出它，只能通过采集/掉落/宝箱等获得；用 item_sources 看来源。");
        potionHint(d, item, o, rs.isEmpty());
        return o;
    }

    /**
     * 药水变体说明。item 是普通物品 ID 且这个物品有多种药水：列出药水，提示结果里每条都标了药水、可加 potion 参数只看一种；
     * item 是变体键（指定了药水）却查不到东西：列出这个物品出现过的药水，方便换一个。没有药水变体的物品不加任何字段。
     */
    static void potionHint(GameData d, String item, JsonObject o, boolean empty) {
        List<String> known = d.potionVariants.getOrDefault(GameData.baseOf(item), List.of());
        if (known.isEmpty()) return;
        boolean specific = GameData.potionOf(item) != null;
        if (specific && !empty) return;
        o.add("potions", strings(known.subList(0, Math.min(POTION_LIST_MAX, known.size()))));
        if (known.size() > POTION_LIST_MAX) o.addProperty("potions_truncated", "共 " + known.size() + " 种，只列了前 " + POTION_LIST_MAX + " 种");
        o.addProperty("potion_hint", specific ? "没有找到这种药水的结果；这个物品出现过的药水见 potions"
                : "这个物品有 " + known.size() + " 种药水（同一物品 ID），结果里每条都标了（药水 …）；只看某一种请加 potion 参数（如 minecraft:poison）");
    }

    static final int POTION_LIST_MAX = 40;

    public static JsonObject recipeUses(GameData d, String item, int limit) {
        JsonObject o = head(d, item);
        List<Recipe> rs = new ArrayList<>(d.byInput.getOrDefault(item, List.of()));
        rs.sort(Comparator.comparingInt((Recipe r) -> RecipeJson.stationRank(r.category())).thenComparing(Recipe::id));
        o.addProperty("total", rs.size());
        o.add("recipes", recipes(d, rs, limit, o));
        return o;
    }

    private static JsonObject head(GameData d, String item) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", d.label(item));
        return o;
    }

    private static JsonArray recipes(GameData d, List<Recipe> rs, int limit, JsonObject parent) {
        JsonArray arr = new JsonArray();
        Set<String> ids = new LinkedHashSet<>();
        for (Recipe r : rs) {
            if (arr.size() >= limit) {
                parent.addProperty("truncated", "只列了前 " + limit + " 条，共 " + rs.size() + " 条；可加大 limit");
                break;
            }
            arr.add(recipe(r, ids));
        }
        parent.add("names", names(d, ids));
        return arr;
    }

    static JsonObject recipe(Recipe r, Set<String> mentioned) {
        JsonObject x = new JsonObject();
        x.addProperty("id", r.id());
        x.addProperty("station", r.station());
        if (!r.type().equals(r.category())) x.addProperty("type", r.type());
        JsonArray in = new JsonArray();
        for (Ingredient i : r.inputs()) {
            in.add(i.display());
            if (!i.fluid() && i.tag() == null) mentioned.addAll(i.options());
            if (i.tag() != null && !i.options().isEmpty()) mentioned.add(i.options().get(0));
        }
        x.add("in", in);
        JsonArray out = new JsonArray();
        for (Stack s : r.outputs()) {
            out.add(s.display());
            if (!s.fluid()) mentioned.add(s.id());
        }
        x.add("out", out);
        if (r.shape() != null && !r.shape().isEmpty()) {
            JsonArray sh = new JsonArray();
            r.shape().forEach(sh::add);
            x.add("shape", sh);
            JsonObject k = new JsonObject();
            if (r.key() != null) r.key().forEach(k::addProperty);
            x.add("key", k);
        }
        if (r.extra() != null) x.addProperty("extra", r.extra());
        if (r.special()) x.addProperty("note", "特殊配方（染色、复制等），结果由放进去的东西决定");
        else if (r.droppedSlots() > 0 && !r.inputs().isEmpty())
            x.addProperty("note", "有 " + r.droppedSlots() + " 个原料槽读不出来（模组自定义的原料写法），上面列的原料不全，请在游戏里用 JEI 看");
        else if (r.inputsUnknown()) x.addProperty("note", "读不到原料（特殊配方，或模组按方块材质动态决定原料），请在游戏里用 JEI 看");
        return x;
    }

    private static JsonObject names(GameData d, Set<String> ids) {
        JsonObject n = new JsonObject();
        int i = 0;
        for (String id : ids) {
            if (++i > 60) break;
            ItemInfo info = d.items.get(id);
            if (info != null) n.addProperty(id, info.label());
        }
        return n;
    }

    // ———————————————————— 标签 ————————————————————

    public static JsonObject tagMembers(GameData d, String tag, int limit) {
        JsonObject o = new JsonObject();
        String t = GameData.norm(tag);
        List<String> items = d.tags.get(t);
        o.addProperty("tag", "#" + t);
        if (items == null) {
            List<String> like = new ArrayList<>();
            for (String k : d.tags.keySet()) if (k.contains(tag.replace("#", "").trim()) && like.size() < 20) like.add("#" + k);
            o.addProperty("error", "没有这个物品标签");
            if (!like.isEmpty()) o.add("similar", strings(like));
            return o;
        }
        o.addProperty("total", items.size());
        JsonArray arr = new JsonArray();
        for (String i : items) {
            if (arr.size() >= limit) break;
            arr.add(i + "（" + d.label(i) + "）");
        }
        o.add("items", arr);
        return o;
    }

    public static JsonArray tagsOf(GameData d, String item) {
        return strings(d.itemTags.getOrDefault(item, List.of()).stream().map(t -> "#" + t).toList());
    }

    // ———————————————————— 掉落与战利品 ————————————————————

    public static JsonObject itemSources(GameData d, String item, int limit) {
        JsonObject o = head(d, item);
        List<Drop> src = new ArrayList<>(d.sources.getOrDefault(item, List.of()));
        // 方块、生物优先；同类里被别的表引用的子表排在后面（它的概率只是"子表被抽到之后"的，父表里经子表的那条才是实际概率），然后按概率从高到低
        src.sort(Comparator.comparingInt((Drop x) -> kindRank(x.kind())).thenComparingInt(x -> subTable(d, x) ? 1 : 0)
                .thenComparing(x -> -x.chance()).thenComparing(Drop::table));
        Map<String, JsonArray> byKind = new LinkedHashMap<>();
        int n = 0;
        for (Drop x : src) {
            if (n >= limit) {
                o.addProperty("truncated", "只列了前 " + limit + " 条，共 " + src.size() + " 条");
                break;
            }
            byKind.computeIfAbsent(LootJson.kindLabel(x.kind()), k -> new JsonArray()).add(drop(d, x, true));
            n++;
        }
        JsonObject k = new JsonObject();
        byKind.forEach(k::add);
        o.add("drops", k);
        o.addProperty("recipes_that_make_it", d.byOutput.getOrDefault(item, List.of()).size());
        o.addProperty("note", "只含数据包里的战利品表；NeoForge 全局战利品修改器追加的掉落、代码写死的掉落、村民交易、刷怪笼/生成规则不在这里。"
                + "引用子表的条目（如钓鱼引用鱼/垃圾/宝藏子表）已按父表选中子表的概率相乘，写成\"父表（经子表 …）\"。");
        potionHint(d, item, o, src.isEmpty());
        return o;
    }

    /** 这条掉落所在的表被别的表引用（是子表）：它的概率只是子表本身被抽到时的条件概率。 */
    static boolean subTable(GameData d, Drop x) {
        return d.referencedBy.containsKey(x.table());
    }

    private static int kindRank(String kind) {
        return switch (kind) {
            case "block" -> 0;
            case "entity" -> 1;
            case "fishing", "shearing" -> 2;
            case "chest" -> 3;
            case "archaeology" -> 4;
            default -> 5;
        };
    }

    static String drop(Drop x, boolean withTable) {
        return drop(null, x, withTable);
    }

    /** d 不为 null 时（item_sources）给子表里的条目加说明（见 {@link #subTable}）。 */
    static String drop(GameData d, Drop x, boolean withTable) {
        StringBuilder sb = new StringBuilder();
        // withTable = true：用在 item_sources（物品已知，写表名）；false：用在 loot_table（表已知，写物品）
        // 标签条目：expand = true 时标签里每种物品各自参与抽取（概率是"抽到这一种"）；false（原版默认）时选中这一条就每种各给一个
        if (withTable) {
            sb.append(x.table());
            if (x.via() != null) sb.append("（经子表 ").append(x.via()).append("）");
            if (d != null && subTable(d, x)) {
                List<String> by = d.referencedBy.get(x.table());
                sb.append("（这是子表，被 ").append(String.join("、", by.subList(0, Math.min(2, by.size())))).append(by.size() > 2 ? " 等" : "")
                        .append(" 引用；概率是子表本身被抽到时的）");
            }
            if (x.tagEntry()) sb.append(x.tagExpand() ? "（标签 #" + x.item() + " 里每种各自参与抽取）" : "（选中时标签 #" + x.item() + " 里每种各给一个）");
            sb.append("：");
        } else {
            sb.append(x.tagEntry() ? "#" : "").append(x.item());
            if (x.tagEntry()) sb.append(x.tagExpand() ? "（每种各自抽取）" : "（选中时每种各一个）");
            sb.append(GameData.potionNote(x.potion()));
            sb.append(' ');
        }
        sb.append("×").append(x.count());
        if (withTable) sb.append(GameData.potionNote(x.potion()));
        if (!"1".equals(x.rolls())) sb.append("，抽 ").append(x.rolls()).append(" 次");
        if (x.chance() >= 0 && x.chance() < 0.995) sb.append("，每次 ").append(GameData.pct(x.chance()));
        if (x.alternative()) sb.append("，多选一");
        if (!withTable && x.via() != null) sb.append("，经子表 ").append(x.via());
        if (!x.conditions().isEmpty()) sb.append("，").append(String.join("、", x.conditions()));
        if (!x.functions().isEmpty()) sb.append("，").append(String.join("、", x.functions()));
        return sb.toString();
    }

    /** 查一张战利品表。query 可以是完整 ID，也可以是 "zombie"、"entities/zombie"、"chests/simple_dungeon"、"diamond_ore"。 */
    public static JsonObject lootTable(GameData d, String query, int limit) {
        JsonObject o = new JsonObject();
        LootTable t = findLoot(d, query);
        if (t == null) {
            List<String> like = new ArrayList<>();
            String q = query.trim().toLowerCase(java.util.Locale.ROOT);
            for (String k : d.loot.keySet()) if (k.contains(q) && like.size() < 25) like.add(k);
            o.addProperty("error", "没有找到这张战利品表");
            if (!like.isEmpty()) o.add("similar", strings(like));
            return o;
        }
        o.addProperty("table", t.id());
        o.addProperty("kind", LootJson.kindLabel(t.kind()));
        JsonArray arr = new JsonArray();
        Set<String> ids = new LinkedHashSet<>();
        for (Drop x : t.drops()) {
            if (arr.size() >= limit) {
                o.addProperty("truncated", "只列了前 " + limit + " 条，共 " + t.drops().size() + " 条");
                break;
            }
            arr.add(drop(x, false));
            if (!x.tagEntry()) ids.add(x.item());
        }
        o.add("drops", arr);
        if (!t.refs().isEmpty()) o.add("includes_tables", strings(t.refs()));
        List<String> by = d.referencedBy.get(t.id());
        if (by != null) {
            o.add("referenced_by", strings(by));
            o.addProperty("sub_table_note", "这是子表：上面的概率是这张表本身被抽到时的；在引用它的父表里还要乘父表选中它的概率（父表的结果里已经乘好了）");
        }
        if (!t.notes().isEmpty()) o.add("notes", strings(t.notes()));
        if (t.error() != null) o.addProperty("error", t.error());
        o.add("names", names(d, ids));
        return o;
    }

    static LootTable findLoot(GameData d, String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        String ns = q.contains(":") ? q.substring(0, q.indexOf(':')) : "minecraft";
        String p = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        for (String c : List.of(ns + ":" + p, ns + ":entities/" + p, ns + ":blocks/" + p, ns + ":chests/" + p, ns + ":gameplay/" + p)) {
            LootTable t = d.loot.get(c);
            if (t != null) return t;
        }
        return null;
    }

    // ———————————————————— 合成链路 ————————————————————

    public static JsonObject plan(GameData d, Plan p, String whoseInventory) {
        JsonObject o = new JsonObject();
        o.addProperty("target", p.target() + "（" + d.label(p.target()) + "）×" + p.count());
        if (whoseInventory != null) o.addProperty("inventory_of", whoseInventory);
        if (p.haveAlready() > 0) o.addProperty("already_have", p.haveAlready());
        o.addProperty("ready", p.ready() ? "背包里的东西已经够了，按步骤做就行" : "还缺原料，见 missing");
        JsonArray steps = new JsonArray();
        int i = 0;
        for (Step s : p.steps()) {
            i++;
            JsonObject x = new JsonObject();
            x.addProperty("n", i);
            x.addProperty("do", s.recipe.station() + " 做 " + s.crafts + " 次");
            x.addProperty("recipe", s.recipe.id());
            JsonArray in = new JsonArray();
            for (Ingredient ing : s.recipe.inputs()) in.add(ing.display() + (s.crafts > 1 ? "（每次）" : ""));
            x.add("each_uses", in);
            JsonArray out = new JsonArray();
            for (Stack st : s.recipe.outputs()) out.add(st.display() + (s.crafts > 1 ? "（每次）" : ""));
            x.add("each_makes", out);
            if (s.recipe.shape() != null && !s.recipe.shape().isEmpty()) {
                JsonArray sh = new JsonArray();
                s.recipe.shape().forEach(sh::add);
                x.add("shape", sh);
                JsonObject k = new JsonObject();
                if (s.recipe.key() != null) s.recipe.key().forEach(k::addProperty);
                x.add("key", k);
            }
            if (s.recipe.extra() != null) x.addProperty("extra", s.recipe.extra());
            steps.add(x);
        }
        o.add("steps", steps);
        JsonArray miss = new JsonArray();
        for (Missing m : p.missing()) {
            JsonObject x = new JsonObject();
            x.addProperty("item", m.item());
            x.addProperty("name", m.item().startsWith("#") ? m.item() : d.label(m.item()));
            x.addProperty("count", m.count());
            x.addProperty("why", whyLabel(m.reason()));
            JsonArray from = new JsonArray();
            for (String s : topSources(d, m.item(), 4)) from.add(s);
            if (!from.isEmpty()) x.add("get_from", from);
            miss.add(x);
        }
        o.add("missing", miss);
        if (!p.fluids().isEmpty()) {
            JsonObject f = new JsonObject();
            p.fluids().forEach((k, v) -> f.addProperty(k, v + "mB"));
            o.add("fluids_needed", f);
        }
        if (!p.usedFromInventory().isEmpty()) o.add("uses_from_inventory", counts(d, p.usedFromInventory()));
        if (!p.leftovers().isEmpty()) o.add("leftovers", counts(d, p.leftovers()));
        if (!p.notes().isEmpty()) o.add("notes", strings(p.notes()));
        o.addProperty("hint", "每个物品只选了一条配方（优先工作台/熔炉、优先用背包里已有的、避开方块↔锭这类互转）。"
                + "想换做法可用 prefer_recipes 指定，或用 recipe_for 看全部配方。");
        int pv = d.potionVariants.getOrDefault(p.target(), List.of()).size();
        if (pv > 0 && GameData.potionOf(p.target()) == null)
            o.addProperty("potion_hint", "这个物品有 " + pv + " 种药水，没指定时规划任选了一种做法（见 each_makes 里的药水）；要特定药水请加 potion 参数");
        if (GameData.potionOf(p.target()) != null || hasPotionStep(p))
            o.addProperty("potion_note", "带药水的物品按药水区分；背包按物品 ID 统计、分不清药水，所以带药水的原料不会从背包里扣");
        return o;
    }

    private static boolean hasPotionStep(Plan p) {
        for (Step s : p.steps()) for (Ingredient i : s.recipe.inputs()) if (i.potion() != null) return true;
        return false;
    }

    private static JsonObject counts(GameData d, Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach((k, v) -> o.addProperty(k + "（" + d.label(k) + "）", v));
        return o;
    }

    static String whyLabel(String reason) {
        if (reason == null) return "";
        return switch (reason) {
            case "no_recipe" -> "没有配方，需要采集/打怪/开箱获得";
            case "unknown_inputs" -> "有配方但原料读不全（特殊配方、模组动态配方或自定义原料写法），请在游戏里用 JEI 看";
            case "gather" -> "直接采集/掉落获得（配方只有方块拆分或要精准采集的原料）";
            case "cycle" -> "只剩会绕回来的配方（如方块↔锭），需要直接获得";
            case "depth" -> "合成层数太深，没再往下展开";
            case "truncated" -> "规划太大，已截断";
            case "unknown_tag" -> "标签里没有任何物品";
            default -> reason;
        };
    }

    /** 某物品最值得一提的几个来源（方块/生物优先，概率高的优先），给玩家看的短句。 */
    public static List<String> topSources(GameData d, String item, int n) {
        List<String> out = new ArrayList<>();
        if (item.startsWith("#")) {
            List<String> members = d.tags.getOrDefault(item.substring(1), List.of());
            if (!members.isEmpty()) out.add("标签里任意一种，例如 " + d.label(members.get(0)));
            return out;
        }
        // 水瓶不是配方也不是常规掉落：玻璃瓶对水右键装。战利品里的水瓶（钓鱼垃圾）概率很低，不能当成主要来源；酿造链的起点总会缺它
        if (WATER_BOTTLE.equals(item)) out.add("玻璃瓶对着水源（或装水的炼药锅）右键装水");
        // 不推荐的来源：方块"掉自己"（得先有一个才能挖，比如市政厅）、模组的内部表（kind=other，例如殖民地矿工的幸运矿石、宠物觅食）。
        // 只有这些来源时如实说明，并提示可能来自任务/商店/指令（整合包常用 KubeJS 删配方、用任务发放）
        List<Drop> src = new ArrayList<>();
        boolean selfOnly = false, internal = false;
        String path = item.substring(item.indexOf(':') + 1);
        for (Drop x : d.sources.getOrDefault(item, List.of())) {
            String t = x.table().substring(x.table().indexOf(':') + 1);
            if (x.kind().equals("block") && t.equals("blocks/" + path)) {
                selfOnly = true; // 先跳过，最后再补一句（原木这类自然生成的方块也是"掉自己"，不能完全不提）
                continue;
            }
            if (x.kind().equals("other")) {
                internal = true;
                continue;
            }
            src.add(x);
        }
        src.sort(Comparator.comparingInt((Drop x) -> kindRank(x.kind()))
                .thenComparingInt(x -> x.needsSilkOrShears() ? 1 : 0)
                .thenComparingInt(x -> subTable(d, x) ? 1 : 0) // 子表的条件概率排后：钓鳕鱼应报父表经子表的实际概率
                .thenComparing(x -> -x.chance())
                .thenComparingInt(x -> x.table().length()) // 同分时短名优先（iron_ore 先于 deepslate_iron_ore），再按表名，保证输出稳定
                .thenComparing(Drop::table));
        Set<String> seen = new LinkedHashSet<>();
        for (Drop x : src) {
            if (out.size() >= n) break;
            String where = x.table().substring(x.table().indexOf(':') + 1);
            String what = switch (x.kind()) {
                case "block" -> "挖 " + labelOfTable(d, x.table(), "blocks/");
                case "entity" -> "打 " + (where.startsWith("entities/") ? where.substring("entities/".length()) : where);
                case "chest" -> "开箱 " + where;
                case "fishing" -> "钓鱼";
                case "archaeology" -> "考古刷 " + where;
                default -> LootJson.kindLabel(x.kind()) + " " + where;
            };
            if (!seen.add(what)) continue;
            String extra = x.chance() >= 0 && x.chance() < 0.995 ? "（" + GameData.pct(x.chance()) + "）" : "";
            if (x.needsSilkOrShears()) extra += "（要精准采集或剪刀）";
            else if (x.conditions().contains("需要玩家击杀")) extra += "（玩家击杀）";
            out.add(what + extra);
        }
        // "掉自己"的方块分不清是野外自然生成的（甘蔗、原木）还是只能放置得到的（市政厅），合成一句如实说明
        if (selfOnly && out.size() < n) {
            out.add("挖 " + d.label(item) + " 方块（它掉自己：野外自然生成的才能挖" + (src.isEmpty() && !d.byOutput.containsKey(item)
                    ? "；野外没有的话，可能来自任务奖励、商店或指令）" : "）"));
        } else if (src.isEmpty() && out.isEmpty() && !d.byOutput.containsKey(item)) {
            out.add("没有找到常规获取途径，可能来自任务奖励、商店或指令（整合包可能删掉了它的配方）");
        }
        return out;
    }

    static final String WATER_BOTTLE = GameData.variant("minecraft:potion", "minecraft:water");

    private static String labelOfTable(GameData d, String table, String prefix) {
        String ns = table.substring(0, table.indexOf(':'));
        String p = table.substring(table.indexOf(':') + 1);
        String id = ns + ":" + (p.startsWith(prefix) ? p.substring(prefix.length()) : p);
        return d.items.containsKey(id) ? d.label(id) : id;
    }

    /** 给玩家看的简短路线（/goal plan、定时提示）：最多 maxSteps 步 + 缺的东西。 */
    public static List<String> planLines(GameData d, Plan p, int maxSteps, int maxMissing) {
        List<String> lines = new ArrayList<>();
        lines.add("目标：" + d.label(p.target()) + " ×" + p.count() + (p.haveAlready() > 0 ? "（已有 " + p.haveAlready() + "）" : ""));
        if (p.steps().isEmpty() && p.missing().isEmpty()) {
            lines.add("背包里已经有了。");
            return lines;
        }
        int i = 0;
        for (Step s : p.steps()) {
            if (++i > maxSteps) {
                lines.add("…还有 " + (p.steps().size() - maxSteps) + " 步");
                break;
            }
            List<String> in = new ArrayList<>();
            for (Ingredient ing : s.recipe.inputs()) in.add(shortIng(d, ing, s.crafts));
            lines.add(i + ". " + s.recipe.station() + "：" + String.join(" + ", in) + " → " + d.label(s.item) + " ×" + made(s));
        }
        if (!p.missing().isEmpty()) {
            List<String> parts = new ArrayList<>();
            int j = 0;
            for (Missing m : p.missing()) {
                if (++j > maxMissing) {
                    parts.add("…等 " + p.missing().size() + " 种");
                    break;
                }
                List<String> src = topSources(d, m.item(), 1);
                parts.add((m.item().startsWith("#") ? m.item() : d.label(m.item())) + "×" + m.count() + (src.isEmpty() ? "" : "（" + src.get(0) + "）"));
            }
            lines.add("还缺：" + String.join("，", parts));
        } else if (p.fluids().isEmpty()) {
            lines.add("材料齐了，按顺序做就行。");
        }
        if (!p.fluids().isEmpty()) {
            List<String> f = new ArrayList<>();
            p.fluids().forEach((k, v) -> f.add(k + " " + v + "mB"));
            lines.add("还要液体：" + String.join("，", f));
        }
        return lines;
    }

    /**
     * 这一步一共得到几个目标物品：有确定产出时 = 次数 × 每次确定产出（long，不会溢出）；只有概率产出时按期望值写"约 N"
     * （旧写法把概率配方当成每次 1 个，和 planner 按期望值算出的次数对不上）。
     */
    static String made(Step s) {
        int sure = s.recipe.surely(s.item);
        if (sure > 0) return String.valueOf((long) s.crafts * sure);
        double e = s.crafts * s.recipe.expected(s.item);
        return "约 " + (e >= 10 ? String.valueOf(Math.round(e)) : String.format(java.util.Locale.ROOT, "%.1f", e));
    }

    private static String shortIng(GameData d, Ingredient ing, int crafts) {
        long n = (long) ing.count() * crafts;
        if (ing.fluid()) return (ing.tag() != null ? "#" + ing.tag() : ing.options().isEmpty() ? "?" : ing.options().get(0)) + " " + n + "mB";
        // 标签原料写第一个成员的名字 +"等"（玩家看得懂），没有成员时才写标签名
        String what = ing.options().isEmpty() ? (ing.tag() != null ? "#" + ing.tag() : "?")
                : d.label(ing.keys().get(0)) + (ing.options().size() > 1 ? "等" : "");
        return what + "×" + n;
    }

    static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }
}
