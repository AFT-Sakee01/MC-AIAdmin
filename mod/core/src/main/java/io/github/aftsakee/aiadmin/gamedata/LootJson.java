package io.github.aftsakee.aiadmin.gamedata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.gamedata.GameData.Drop;
import io.github.aftsakee.aiadmin.gamedata.GameData.LootTable;
import io.github.aftsakee.aiadmin.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 把"战利品表编码成的 JSON"（LootTable.DIRECT_CODEC 的输出）展平成物品条目。
 * <p>概率只算得出能静态确定的部分：池内权重占比 × random_chance / table_bonus（取无附魔时的值）/ 带抢夺加成的随机概率（取无附魔值）。
 * 需要玩家击杀、精准采集、剪刀、作物成熟等条件不改变概率，只写进 conditions，由模型自己理解（按"满足"计）。
 * <p>池内占比按原版 LootPool#addRandomItem 的语义：每个顶层条目先展开（expand）成候选——单条目（物品、标签、引用表、空）自己就是候选；
 * 组合条目（alternatives / group / sequence，原版 CompositeEntryBase）没有自己的权重，把满足条件的子条目展开成候选，
 * 候选各带自己的权重（幸运 0 时 = weight）参与加权抽取。所以子条目的权重计入池的总权重：
 * <ul>
 *   <li>group：子条目全部展开，各自是候选；</li>
 *   <li>alternatives：按顺序取第一个展开成功的子条目（子条目标 alternative = true）；排在一个"必定成功"（没有条件）的子条目后面的永远轮不到，不输出；</li>
 *   <li>sequence：按顺序展开直到某个子条目失败；后面的子条目继承前面子条目的条件（前面的都满足才轮得到）。</li>
 * </ul>
 * 静态近似（原因：候选集合取决于运行时条件，而条件按"满足"计、彼此常常互斥）：算某个候选的占比时，它所在的情形按"它的条件都满足"，
 * 其它条目按"带条件的都不满足"展开（{@link #defaultWeight}），即 占比 = 自己的权重 /（自己 + 同时在场的其它候选的权重）。
 * 例如"青蛙击杀 / 不是青蛙击杀"、"精准采集掉自身 / 否则掉粗矿"这类互斥条目各自是 100%，不会被平分成 50%。
 * 例外：池里只有一个顶层条目带条件时按"额外条目"处理（其它条目的占比按它在场/不在场加权，见 {@link #pools}）。
 * <p>-1 哨兵 = 算不出来（数值提供器不是常量/均匀分布、table_bonus 没有档位…）。组合规则：任何一个因子是 -1，乘积就是 -1，
 * 不能让 -1 参与乘法（两个 -1 相乘会变成 +1，显示成 100%）。池条件、条目条件、权重占比、子表的外层概率都遵守"见 -1 即 -1"；
 * 说明文字照常全部收集。
 * <p>标签条目：expand = true 时标签里 N 个物品各是一个条目（各带权重 w，池总权重加 w×N）；expand = false（原版默认）时这一条只占 w，
 * 选中后标签里每种各给一个。需要标签成员数，所以平台层要传 tagItems；拿不到（tagItems = null）时 expand 条目按一个条目估算并在函数说明里注明。
 * <p>引用子表（{"type":"loot_table","value":"ns:id"}，如钓鱼引用鱼/垃圾/宝藏三张子表）：平台层传了 resolver 时把子表展开进本表——
 * 子表物品的概率 = 父池里这个引用条目的选中概率 × 子表里该物品的概率，条件、函数叠加，抽取次数相乘（原版 NestedLootTable 选中时把整张子表抽一遍）。
 * 同一子表被多处引用时分别列出（和同一物品出现在多个池里一样，一条一条写）。递归展开，正在展开的表再次出现 = 成环，不再展开；
 * 最多展开 {@link #MAX_REF_DEPTH} 层、一张表最多展开出 {@link #MAX_DROPS} 条；这些情况和"引用的表不存在"都写进表的 notes。
 * 内联子表（value 是对象）同样展开，不算层数。
 * 没有 resolver 时（旧调用）只登记在 refs 里，子表物品不进本表。
 * <p>幸运（quality）：原版候选权重 = floor(weight + quality × 幸运)，钓鱼的垃圾/宝藏子表引用带 quality（海之眷顾、幸运效果会改变比例）。
 * 一律按幸运 0 计算；池里有 quality ≠ 0 的条目时，这个池的每个条目都在函数说明里注明（一个条目的权重变了，其它条目的占比也跟着变）。
 * <p>药水：set_potion、set_components（带 minecraft:potion_contents）给出的药水写进 {@link Drop#potion}；带条件的只写进说明。
 * <p>不覆盖：NeoForge 全局战利品修改器（Global Loot Modifiers）在运行时追加的掉落、代码里写死的掉落（如末影龙蛋）、村民交易。
 */
public final class LootJson {
    /**
     * 引用子表最多展开几层（根表算第 1 层）。原版钓鱼、试炼宝库奖励都只有 2 层；模组偶尔多一层。
     * 上限是防御：成环已单独判断，这里挡的是很长的引用链把一张表展开成成千上万条。
     */
    static final int MAX_REF_DEPTH = 6;
    /**
     * 一张表展开后最多多少条（计子表里的条目）。原因：层数限制挡不住"横向"膨胀——一个池里十几个条目都引用同一张大表、
     * 子表再引用大表时条目数是乘积。超过后不再展开新的引用子表（已展开的保留），写进 notes。原版最大的表也只有几十条。
     */
    static final int MAX_DROPS = 4000;
    static final String LUCK_NOTE = "按幸运 0 计算（幸运/海之眷顾会改变各条目的比例）";
    static final String EXTRA_NOTE = "按池里带条件的那一条也在场计算（它的条件不满足时这里的比例更高）";

    private LootJson() {}

    public static String kind(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        if (p.startsWith("blocks/")) return "block";
        if (p.startsWith("entities/")) return "entity";
        if (p.startsWith("chests/") || p.startsWith("pots/")) return "chest";
        if (p.startsWith("gameplay/fishing")) return "fishing";
        if (p.startsWith("archaeology/")) return "archaeology";
        if (p.startsWith("gameplay/hero_of_the_village/") || p.contains("gift")) return "gift";
        if (p.startsWith("shearing/")) return "shearing";
        if (p.startsWith("spawners/")) return "spawner";
        if (p.startsWith("equipment/")) return "equipment";
        if (p.startsWith("gameplay/")) return "gameplay";
        return "other";
    }

    public static String kindLabel(String kind) {
        return switch (kind) {
            case "block" -> "方块掉落";
            case "entity" -> "生物掉落";
            case "chest" -> "宝箱/容器";
            case "fishing" -> "钓鱼";
            case "archaeology" -> "考古";
            case "gift" -> "村民礼物";
            case "shearing" -> "剪毛/修剪";
            case "spawner" -> "试炼刷怪笼奖励";
            case "equipment" -> "生物装备";
            case "gameplay" -> "玩法奖励";
            default -> "其它";
        };
    }

    /** 编码后的 JSON 里的 type（minecraft:chest、minecraft:entity…）比路径可靠（结构模组的箱子表常放在别的路径下）。 */
    static String kindOf(String id, JsonObject j) {
        String t = Json.str(j.get("type"));
        if (t != null) {
            switch (t) {
                case "minecraft:chest": return "chest";
                case "minecraft:entity": return "entity";
                case "minecraft:fishing": return "fishing";
                case "minecraft:archaeology": return "archaeology";
                case "minecraft:gift": return "gift";
                case "minecraft:shearing": return "shearing";
                default: break; // block、generic 等：按路径
            }
        }
        return kind(id);
    }

    /** 不知道标签成员数时的解析（expand 标签条目按一个条目估算）。平台层应使用 {@link #parse(String, JsonObject, Function, Function)}。 */
    public static LootTable parse(String id, JsonObject j) {
        return parse(id, j, null, null);
    }

    /** 不展开引用的子表（只登记在 refs）。 */
    public static LootTable parse(String id, JsonObject j, Function<String, List<String>> tagItems) {
        return parse(id, j, tagItems, null);
    }

    /**
     * tagItems：标签 ID（不带 #）→ 成员物品；不存在的标签返回空列表或 null（都按没有成员）。传 null = 拿不到标签成员。
     * resolver：表 ID → 那张表编码后的 JSON（不存在返回 null）；传 null = 不展开引用的子表。
     * 线程：纯计算，resolver 只读平台层事先建好的 JSON 映射，不碰游戏状态。
     */
    public static LootTable parse(String id, JsonObject j, Function<String, List<String>> tagItems, Function<String, JsonObject> resolver) {
        Parse p = new Parse(tagItems, resolver);
        p.stack.add(id);
        List<Drop> drops = new ArrayList<>();
        String kind = kindOf(id, j);
        pools(p, id, kind, j, null, drops);
        return new LootTable(id, kind, drops, List.copyOf(p.refs), null, List.copyOf(p.notes));
    }

    /** 一次解析的共享状态。stack = 正在展开的引用链（根表在最前），用来判断成环和层数；refs 只收根表（含内联子表）直接引用的表。 */
    private static final class Parse {
        final Function<String, List<String>> tagItems;
        final Function<String, JsonObject> resolver;
        final List<String> stack = new ArrayList<>();
        final Set<String> refs = new LinkedHashSet<>();
        final Set<String> notes = new LinkedHashSet<>();
        /** 已经生成的条目数（含子表里的），见 {@link #MAX_DROPS}。 */
        int made;

        Parse(Function<String, List<String>> tagItems, Function<String, JsonObject> resolver) {
            this.tagItems = tagItems;
            this.resolver = resolver;
        }
    }

    /**
     * 沿条目树往下传的上下文。prob = 池条件 × 祖先组合条目条件的概率（-1 哨兵）；competitors = 这个条目被抽时同时在场的其它候选的权重
     * （见类注释的静态近似）；extra/extraP = 池里唯一一个带条件的顶层条目（"额外"条目）的权重和它在场的概率（见 {@link #pools}），
     * 其它条目的占比按它在场/不在场两种情形加权；cond/fn = 继承下来的条件、函数说明；count/potion = 继承的数量和药水
     * （池函数、引用条目的函数）；via = 经过的引用子表链。
     */
    private record Ctx(String table, String kind, String rolls, List<String> cond, List<String> fn, String count, String potion,
                       boolean alternative, double prob, long competitors, long extra, double extraP, String via) {
        Ctx with(List<String> c, double p, long comp, boolean alt) {
            return new Ctx(table, kind, rolls, c, fn, count, potion, alt, p, comp, extra, extraP, via);
        }
    }

    private static void pools(Parse p, String table, String kind, JsonObject j, String via, List<Drop> out) {
        for (JsonElement pe : Json.arr(j, "pools")) {
            if (!pe.isJsonObject()) continue;
            JsonObject pool = pe.getAsJsonObject();
            String rolls = number(pool.get("rolls"), "1");
            List<String> poolCond = new ArrayList<>();
            double poolChance = conditions(pool.get("conditions"), poolCond);
            List<String> poolFn = new ArrayList<>();
            Fn pf = functions(pool.get("functions"), poolFn);
            List<JsonObject> entries = objects(pool.get("entries"));
            if (anyQuality(entries)) poolFn.add(LUCK_NOTE);
            // 每个顶层条目在"别的条件都不满足"时展开出的权重；某个条目的竞争者 = 其它顶层条目的这个值之和。
            // 权重用 long：展开的标签 = 权重 × 成员数，服主写的大权重乘上去可能超过 int。
            long[] dw = new long[entries.size()];
            long total = 0;
            for (int i = 0; i < dw.length; i++) total += dw[i] = defaultWeight(entries.get(i), p.tagItems, false);
            // 池里只有一个顶层条目带条件时，它多半是"额外"的（钓鱼的宝藏子表只在开阔水域参与），不是和别的条件条目互斥的一组：
            // 其它条目的占比按"它在场（概率 = 它的随机条件概率，说明类条件按满足计）/ 不在场"两种情形加权，和它自己显示的概率是同一情形，
            // 整池加起来是 100%（钓鱼：鱼 85%、垃圾 10%、宝藏 5%，而不是鱼 85/95）。有说明类条件时在其它条目上注明。
            // 多个条件条目（"青蛙击杀 / 不是青蛙击杀"）仍按互斥处理，见类注释
            int condIdx = -1, condCount = 0;
            for (int i = 0; i < dw.length; i++) {
                if (conditional(entries.get(i))) {
                    condCount++;
                    condIdx = i;
                }
            }
            long extra = 0;
            double extraP = 0;
            List<String> otherFn = poolFn;
            if (condCount == 1 && dw.length > 1) {
                JsonObject ce = entries.get(condIdx);
                extra = defaultWeight(ce, p.tagItems, true);
                List<String> desc = new ArrayList<>();
                double cp = conditions(ce.get("conditions"), desc);
                extraP = cp < 0 ? 1 : cp; // 算不出的随机条件按满足计（和说明类条件一致）
                if (extra > 0 && !desc.isEmpty()) {
                    otherFn = new ArrayList<>(poolFn);
                    otherFn.add(EXTRA_NOTE);
                }
            }
            for (int i = 0; i < dw.length; i++) {
                boolean self = i == condIdx;
                Ctx c = new Ctx(table, kind, rolls, poolCond, self ? poolFn : otherFn, pf.count(), pf.potion(), false, poolChance, total - dw[i],
                        self ? 0 : extra, extraP, via);
                entry(p, entries.get(i), c, out);
            }
        }
    }

    private static List<JsonObject> objects(JsonElement arr) {
        List<JsonObject> l = new ArrayList<>();
        if (arr != null && arr.isJsonArray()) for (JsonElement e : arr.getAsJsonArray()) if (e.isJsonObject()) l.add(e.getAsJsonObject());
        return l;
    }

    private static String type(JsonObject e) {
        String t = Json.path(Json.str(e.get("type")));
        return t == null ? "" : t;
    }

    private static boolean composite(String type) {
        return type.equals("alternatives") || type.equals("group") || type.equals("sequence");
    }

    /** 条目自己带了会影响"是否参与"的条件（爆炸存活这种几乎总满足的不算）。 */
    private static boolean conditional(JsonObject e) {
        for (JsonElement c : Json.arr(e, "conditions")) {
            if (c.isJsonObject() && !"survives_explosion".equals(Json.path(Json.str(c.getAsJsonObject().get("condition"))))) return true;
        }
        return false;
    }

    /**
     * "带条件的都不满足"时这个条目展开出的候选权重之和（原版 expand 的静态版本）。ignoreOwn = 不看条目自己的条件
     * （sequence 里排在前面、必须已经成功的子条目用）。单条目 = 自己的池权重；group = 子条目之和；alternatives = 第一个必定成功的子条目；
     * sequence = 从头累加到第一个可能失败的子条目之前。
     */
    static long defaultWeight(JsonObject e, Function<String, List<String>> tagItems, boolean ignoreOwn) {
        if (!ignoreOwn && conditional(e)) return 0;
        String t = type(e);
        List<JsonObject> kids = composite(t) ? objects(e.get("children")) : List.of();
        switch (t) {
            case "group": {
                long s = 0;
                for (JsonObject k : kids) s += defaultWeight(k, tagItems, false);
                return s;
            }
            case "alternatives": {
                for (JsonObject k : kids) if (defaultSucceeds(k)) return defaultWeight(k, tagItems, false);
                return 0;
            }
            case "sequence": {
                long s = 0;
                for (JsonObject k : kids) {
                    if (!defaultSucceeds(k)) break;
                    s += defaultWeight(k, tagItems, false);
                }
                return s;
            }
            default:
                return poolWeight(e, tagItems); // 物品、标签、引用表、空、动态（潜影盒内容）及模组的单条目类型都占权重
        }
    }

    /** "带条件的都不满足"时这个条目展开是否成功（原版 expand 的返回值）：group 总成功，alternatives 任一成功，sequence 全部成功。 */
    static boolean defaultSucceeds(JsonObject e) {
        if (conditional(e)) return false;
        String t = type(e);
        if (!composite(t)) return true; // 展开后没有成员的标签也算成功（原版 expandTag 照样返回 true）
        List<JsonObject> kids = objects(e.get("children"));
        return switch (t) {
            case "alternatives" -> kids.stream().anyMatch(LootJson::defaultSucceeds);
            case "sequence" -> kids.stream().allMatch(LootJson::defaultSucceeds);
            default -> true;
        };
    }

    /** 池里（含组合条目的子条目）有没有 quality ≠ 0 的条目：有就说明这个池的比例受幸运影响。 */
    private static boolean anyQuality(List<JsonObject> entries) {
        for (JsonObject e : entries) {
            if (Json.intOf(e, "quality", 0) != 0) return true;
            if (composite(type(e)) && anyQuality(objects(e.get("children")))) return true;
        }
        return false;
    }

    private static int weight(JsonObject e) {
        int w = Json.intOf(e, "weight", 1);
        return Math.max(0, w);
    }

    /** 条目在池里一共占多少权重：展开的标签 = 权重 × 成员数（原版把每个成员当成独立条目）；其它（含成员数未知）= 权重。 */
    private static long poolWeight(JsonObject e, Function<String, List<String>> tagItems) {
        Integer n = expandedMembers(e, tagItems);
        return n == null ? weight(e) : (long) weight(e) * n;
    }

    /** expand = true 的标签条目的成员数；不是展开的标签、或拿不到标签成员（tagItems 为 null）返回 null。 */
    private static Integer expandedMembers(JsonObject e, Function<String, List<String>> tagItems) {
        if (tagItems == null || !"tag".equals(Json.path(Json.str(e.get("type")))) || !Json.bool(e, "expand", false)) return null;
        String name = Json.str(e.get("name"));
        if (name == null) return null;
        List<String> m = tagItems.apply(name);
        return m == null ? 0 : m.size();
    }

    /** 哨兵乘法：任何一个是 -1（算不出）结果就是 -1。 */
    private static double mul(double a, double b) {
        return a < 0 || b < 0 ? -1 : a * b;
    }

    private static void entry(Parse p, JsonObject e, Ctx c, List<Drop> out) {
        String type = type(e);
        List<String> cond = new ArrayList<>(c.cond());
        double prob = mul(c.prob(), conditions(e.get("conditions"), cond));
        if (composite(type)) compositeEntry(p, e, type, c.with(cond, prob, c.competitors(), c.alternative()), out);
        else leaf(p, e, type, cond, prob, c, out);
    }

    /** 组合条目：没有自己的权重和函数（原版 CompositeEntryBase 的编码只有 children 和 conditions），子条目各自作为候选，竞争者按类型调整。 */
    private static void compositeEntry(Parse p, JsonObject e, String type, Ctx c, List<Drop> out) {
        List<JsonObject> kids = objects(e.get("children"));
        switch (type) {
            case "group" -> {
                // 子条目全部展开：每个子条目的竞争者还要加上兄弟子条目展开出的权重
                long[] dw = new long[kids.size()];
                long sum = 0;
                for (int i = 0; i < dw.length; i++) sum += dw[i] = defaultWeight(kids.get(i), p.tagItems, false);
                for (int i = 0; i < dw.length; i++) entry(p, kids.get(i), c.with(c.cond(), c.prob(), c.competitors() + sum - dw[i], c.alternative()), out);
            }
            case "alternatives" -> {
                // 多选一：兄弟子条目互斥，不互相竞争；一个必定成功的子条目之后的都轮不到（原版 validate 报 "Unreachable entry!"）。
                // 近似：后面的子条目按"前面的条件不满足"的情形算，不乘前面条目失败的概率（说明类条件按"满足"计，乘了会变成 0）
                for (JsonObject k : kids) {
                    entry(p, k, c.with(c.cond(), c.prob(), c.competitors(), true), out);
                    if (defaultSucceeds(k)) break;
                }
            }
            default -> { // sequence
                // 依次展开直到失败：第 i 个子条目在场时，前面的都已成功（各自展开出权重），后面的继续展开到第一个可能失败的为止
                List<String> before = new ArrayList<>(c.cond());
                double pre = c.prob();
                for (int i = 0; i < kids.size(); i++) {
                    long comp = c.competitors();
                    for (int j = 0; j < i; j++) comp += defaultWeight(kids.get(j), p.tagItems, true);
                    for (int j = i + 1; j < kids.size() && defaultSucceeds(kids.get(j)); j++) comp += defaultWeight(kids.get(j), p.tagItems, false);
                    entry(p, kids.get(i), c.with(before, pre, comp, c.alternative()), out);
                    // 后面的子条目只有这一个也成功才轮得到：继承它自己的条件（概率和说明）
                    before = new ArrayList<>(before);
                    pre = mul(pre, conditions(kids.get(i).get("conditions"), before));
                }
            }
        }
    }

    /** 单条目（物品、标签、引用表、空、动态）：自己是一个候选（展开的标签是每个成员一个候选）。 */
    private static void leaf(Parse p, JsonObject e, String type, List<String> cond, double prob, Ctx c, List<Drop> out) {
        long w = weight(e);
        Integer members = expandedMembers(e, p.tagItems);
        long own = members == null ? w : w * members;
        // 权重 0 的候选原版直接跳过（永远抽不到）；展开后一个成员都没有的标签也抽不到
        double share = 0;
        if (w > 0 && (members == null || members > 0)) {
            double without = (double) w / (own + c.competitors());
            share = c.extra() <= 0 ? without : c.extraP() * w / (own + c.competitors() + c.extra()) + (1 - c.extraP()) * without;
        }
        double ch = mul(share, prob);
        List<String> fn = new ArrayList<>(c.fn());
        Fn f = functions(e.get("functions"), fn);
        String count = f.count() != null ? f.count() : c.count();
        String potion = f.potion() != null ? f.potion() : c.potion();
        switch (type) {
            case "item" -> {
                String name = Json.str(e.get("name"));
                if (name == null) return;
                p.made++;
                out.add(new Drop(c.table(), c.kind(), name, false, ch, c.rolls(), count == null ? "1" : count, dedup(cond), dedup(fn),
                        c.alternative(), false, potion, c.via()));
            }
            case "tag" -> {
                String name = Json.str(e.get("name"));
                if (name == null) return;
                boolean expand = Json.bool(e, "expand", false);
                if (expand && p.tagItems == null) fn.add("概率按整个标签算一个条目估算（解析时拿不到标签成员数）");
                p.made++;
                out.add(new Drop(c.table(), c.kind(), name, true, ch, c.rolls(), count == null ? "1" : count, dedup(cond), dedup(fn),
                        c.alternative(), expand, potion, c.via()));
            }
            case "loot_table" -> nested(p, e, c, ch, cond, fn, count, potion, out);
            default -> { /* empty / dynamic（如潜影盒内容）不算掉落，但上面已经算进了权重 */ }
        }
    }

    /**
     * 子表（引用或内联）：把子表展开，外层这一条被选中的概率、条件、函数、数量、药水都叠加到子表的每个条目上（原版里外层的函数
     * 作用在子表产出的物品上，所以外层 set_count / set_potion 覆盖子表的）；抽取次数相乘。
     */
    private static void nested(Parse p, JsonObject e, Ctx c, double ch, List<String> cond, List<String> fn, String count, String potion,
                               List<Drop> out) {
        JsonElement v = e.get("value");
        String ref = Json.str(v);
        List<Drop> inner = new ArrayList<>();
        if (ref != null) {
            if (p.stack.size() == 1) p.refs.add(ref); // 只登记根表（含内联子表）直接引用的；子表里的引用属于子表
            JsonObject sub = resolve(p, ref);
            if (sub == null) return;
            String via = c.via() == null ? ref : c.via() + " → " + ref;
            p.stack.add(ref);
            try {
                pools(p, c.table(), c.kind(), sub, via, inner);
            } finally {
                p.stack.remove(p.stack.size() - 1);
            }
        } else if (v != null && v.isJsonObject()) {
            pools(p, c.table(), c.kind(), v.getAsJsonObject(), c.via(), inner);
        } else {
            return;
        }
        for (Drop d : inner) {
            List<String> cs = new ArrayList<>(cond);
            cs.addAll(d.conditions());
            List<String> fs = new ArrayList<>(fn);
            fs.addAll(d.functions());
            out.add(new Drop(c.table(), c.kind(), d.item(), d.tagEntry(), mul(ch, d.chance()), rolls(c.rolls(), d.rolls()),
                    count != null ? count : d.count(), dedup(cs), dedup(fs), c.alternative() || d.alternative(), d.tagExpand(),
                    potion != null ? potion : d.potion(), d.via()));
        }
    }

    /** 引用的子表能不能展开：没有 resolver、成环、层数超限、表不存在都返回 null（后三种写进 notes）。 */
    private static JsonObject resolve(Parse p, String ref) {
        if (p.resolver == null) return null;
        if (p.stack.contains(ref)) {
            p.notes.add("引用成环（" + String.join(" → ", p.stack) + " → " + ref + "），没有再展开");
            return null;
        }
        if (p.made >= MAX_DROPS) {
            p.notes.add("展开后条目超过 " + MAX_DROPS + " 条，" + ref + " 里的物品没有再算进来");
            return null;
        }
        if (p.stack.size() >= MAX_REF_DEPTH) {
            p.notes.add("引用超过 " + MAX_REF_DEPTH + " 层，" + ref + " 里的物品没有算进来");
            return null;
        }
        JsonObject sub = p.resolver.apply(ref);
        if (sub == null) p.notes.add("引用的表 " + ref + " 不存在或读不到，里面的物品没有算进来");
        return sub;
    }

    /** 外层抽取次数 × 子表池的抽取次数（"1" 不写）。 */
    private static String rolls(String outer, String inner) {
        if ("1".equals(outer)) return inner;
        if ("1".equals(inner)) return outer;
        return outer + "×" + inner;
    }

    private static List<String> dedup(List<String> l) {
        return List.copyOf(new LinkedHashSet<>(l));
    }

    /** 解析条件列表：把能确定的概率乘起来返回（无条件 = 1；任何一项算不出 = -1，见类注释的哨兵规则），其余写成说明加进 out。 */
    static double conditions(JsonElement e, List<String> out) {
        if (e == null || !e.isJsonArray()) return 1;
        double p = 1;
        boolean unknown = false;
        for (JsonElement ce : e.getAsJsonArray()) {
            if (!ce.isJsonObject()) continue;
            double v = condition(ce.getAsJsonObject(), out, false);
            if (v < 0) unknown = true; // 不 break：后面条件的说明照样要收集
            else p *= v;
        }
        return unknown ? -1 : p;
    }

    private static double condition(JsonObject c, List<String> out, boolean inverted) {
        String t = Json.path(Json.str(c.get("condition")));
        if (t == null) return 1;
        String raw = c.toString();
        String not = inverted ? "不满足：" : "";
        switch (t) {
            case "survives_explosion":
                return 1; // 爆炸时可能不掉，太常见，不写
            case "killed_by_player":
                out.add(inverted ? "不是玩家击杀时" : "需要玩家击杀");
                return 1;
            case "random_chance": {
                double v = c.has("chance") ? numberValue(c.get("chance")) : 1;
                if (inverted) return v < 0 ? -1 : 1 - v;
                return v < 0 ? -1 : v;
            }
            case "random_chance_with_enchanted_bonus":
            case "random_chance_with_looting": {
                double v = c.has("unenchanted_chance") ? numberValue(c.get("unenchanted_chance")) : c.has("chance") ? numberValue(c.get("chance")) : -1;
                out.add("抢夺可提高概率");
                if (inverted) return v < 0 ? -1 : 1 - v;
                return v < 0 ? -1 : v;
            }
            case "table_bonus": {
                JsonElement ch = c.get("chances");
                out.add(enchantName(raw) + "可提高概率");
                if (ch != null && ch.isJsonArray() && !ch.getAsJsonArray().isEmpty()) {
                    double v = numberValue(ch.getAsJsonArray().get(0));
                    return inverted ? (v < 0 ? -1 : 1 - v) : v;
                }
                return -1;
            }
            case "match_tool":
            case "can_item_perform_ability": { // 后者是 NeoForge 的"工具能力"判断（剪刀能力 shears_dig 等）
                String need = raw.contains("silk_touch") ? "精准采集" : raw.contains("shears") ? "剪刀" : "特定工具";
                out.add(inverted ? "不用" + need + "时" : "需要" + need);
                return 1;
            }
            case "inverted": {
                if (c.has("term") && c.get("term").isJsonObject()) return condition(c.getAsJsonObject("term"), out, !inverted);
                return 1;
            }
            case "any_of", "all_of", "alternative": {
                List<String> sub = new ArrayList<>();
                // 德摩根：取反后"或"变"且"、"且"变"或"（子条件已按取反求值）
                boolean and = t.equals("all_of") != inverted;
                // 概率：且 = 各项相乘；或 = 1 - Π(1 - pᵢ)（按各项独立估算）。说明类条件按"满足"计（返回 1），所以"或"里有一项说明类就是 1。
                // 任何一项算不出 → -1；一项都没有 → 1（保持旧行为，不当成"永远不满足"）
                double prod = 1, none = 1;
                boolean unknown = false;
                int n = 0;
                for (JsonElement te : Json.arr(c, "terms")) {
                    if (!te.isJsonObject()) continue;
                    double v = condition(te.getAsJsonObject(), sub, inverted);
                    n++;
                    if (v < 0) unknown = true;
                    else if (and) prod *= v;
                    else none *= 1 - v;
                }
                if (!sub.isEmpty()) out.add(String.join(and ? " 且 " : " 或 ", new LinkedHashSet<>(sub)));
                if (unknown) return -1;
                if (n == 0) return 1;
                return and ? prod : 1 - none;
            }
            case "block_state_property":
                out.add(not + (raw.contains("\"age\"") ? "作物/植物需成熟" : "方块状态要求"));
                return 1;
            case "entity_properties":
                if (raw.contains("is_on_fire")) out.add(inverted ? "没着火时" : "着火时");
                else out.add(not + (raw.contains("is_baby") ? "幼年/成年条件" : "实体条件"));
                return 1;
            case "damage_source_properties":
                out.add(inverted ? "不是特定死亡方式" : "特定死亡方式");
                return 1;
            case "location_check":
                out.add(not + "位置/群系条件");
                return 1;
            case "weather_check":
                out.add(not + "天气条件");
                return 1;
            case "time_check":
                out.add(not + "时间条件");
                return 1;
            default:
                out.add(not + "条件 " + t);
                return 1;
        }
    }

    /** 函数列表的结果：count = set_count 得到的数量，potion = set_potion / set_components 给出的药水（都是无条件的才算；没有为 null）。 */
    record Fn(String count, String potion) {}

    /** 解析函数列表，返回数量和药水（见 {@link Fn}），其余写成说明。 */
    static Fn functions(JsonElement e, List<String> out) {
        if (e == null || !e.isJsonArray()) return new Fn(null, null);
        String count = null, potion = null;
        for (JsonElement fe : e.getAsJsonArray()) {
            if (!fe.isJsonObject()) continue;
            JsonObject f = fe.getAsJsonObject();
            String t = Json.path(Json.str(f.get("function")));
            if (t == null) continue;
            List<String> cond = new ArrayList<>();
            conditions(f.get("conditions"), cond);
            String when = cond.isEmpty() ? "" : "（" + String.join("，", cond) + "）";
            switch (t) {
                case "set_count" -> {
                    String n = number(f.get("count"), "1");
                    if (Json.bool(f, "add", false)) out.add("额外 +" + n + when);
                    else if (!when.isEmpty()) out.add("数量 " + n + when);
                    else count = n;
                }
                case "set_potion" -> {
                    String id = Json.str(f.get("id"));
                    String pt = id == null || id.isBlank() ? null : GameData.norm(id);
                    if (pt == null) out.add("指定药水" + when);
                    else if (!when.isEmpty()) out.add("药水 " + pt + when); // 带条件：不一定是这种，只写进说明
                    else potion = pt;
                }
                case "set_components" -> {
                    // 组件补丁里只看药水内容；其它组件（名字、附魔光效…）对"掉什么"影响不大
                    String pt = ItemComponents.potion(f.get("components"));
                    if (pt != null) {
                        if (!when.isEmpty()) out.add("药水 " + pt + when);
                        else potion = pt;
                    }
                }
                case "apply_bonus" -> out.add(enchantName(f.toString()) + "加成");
                case "enchanted_count_increase", "looting_enchant" -> out.add("抢夺加成");
                case "furnace_smelt" -> out.add("烧熟" + when);
                case "explosion_decay", "limit_count", "set_nbt", "copy_components", "copy_name", "copy_state", "set_name",
                     "set_lore", "set_custom_data" -> { /* 对"掉什么、掉几个"影响不大 */ }
                case "enchant_randomly" -> out.add("随机附魔");
                case "enchant_with_levels" -> out.add("按等级附魔");
                case "set_damage" -> out.add("随机耐久");
                case "exploration_map" -> out.add("探险地图");
                case "set_stew_effect" -> out.add("随机迷之炖菜效果");
                default -> out.add(t);
            }
        }
        return new Fn(count, potion);
    }

    private static String enchantName(String raw) {
        if (raw.contains("fortune")) return "时运";
        if (raw.contains("looting")) return "抢夺";
        return "附魔";
    }

    /** 数值提供器 → 文字："1"、"2-4"、"约 3"。 */
    static String number(JsonElement e, String def) {
        if (e == null || e.isJsonNull()) return def;
        if (Json.isNumber(e)) return fmt(e.getAsDouble());
        if (!e.isJsonObject()) return def;
        JsonObject o = e.getAsJsonObject();
        String t = Json.path(Json.str(o.get("type")));
        if ("constant".equals(t) && o.has("value")) return number(o.get("value"), def);
        if (o.has("min") && o.has("max")) return number(o.get("min"), "?") + "-" + number(o.get("max"), "?");
        if ("binomial".equals(t) && o.has("n")) return "0-" + number(o.get("n"), "?");
        return def;
    }

    /** 数值提供器 → 数（取常量或均值；算不出返回 -1 哨兵）。 */
    static double numberValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return -1;
        if (Json.isNumber(e)) return e.getAsDouble();
        if (!e.isJsonObject()) return -1;
        JsonObject o = e.getAsJsonObject();
        if (o.has("value")) return numberValue(o.get("value"));
        if (o.has("min") && o.has("max")) {
            double a = numberValue(o.get("min")), b = numberValue(o.get("max"));
            return a < 0 || b < 0 ? -1 : (a + b) / 2;
        }
        return -1;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }
}
