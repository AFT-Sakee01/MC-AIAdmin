package io.github.aftsakee.aiadmin.gamedata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 游戏资料快照：物品、配方、物品标签、战利品表（方块/实体掉落、宝箱、钓鱼、考古…）。
 * <p>由平台层（neoforge 的 GameDataIndex）在服务端主线程一次性构建，构建完成后<b>不可变</b>，
 * 所以查询可以在任何线程进行（代理线程、远程接口线程）。数据包 /reload 后平台层会整份重建一个新快照，不原地修改。
 * <p>不可变靠防御性拷贝保证（不只靠约定）：各 record 的紧凑构造器把传入的列表/映射拷成不可变副本，构造函数把 tags 的每个值也拷一份，
 * 平台层之后再改自己手里的 ArrayList 不会影响快照。拷贝时去掉 null 元素（List.copyOf 遇到 null 会抛异常，让整份快照建不起来）。
 * <p>所有 ID 都是完整的 "命名空间:路径"（物品 minecraft:iron_ingot、标签 minecraft:planks 不带 #）。
 */
public final class GameData {
    /** 物品：英文名来自服务端语言（en_us），中文名来自各模组 jar 自带的 zh_cn.json（原版物品服务端没有中文名）。 */
    public record ItemInfo(String id, String en, String zh) {
        public String mod() {
            int i = id.indexOf(':');
            return i < 0 ? "minecraft" : id.substring(0, i);
        }

        /** "Iron Ingot / 铁锭"，没有中文名时只给英文名。 */
        public String label() {
            if (zh == null || zh.isBlank() || zh.equals(en)) return en == null || en.isBlank() ? id : en;
            return (en == null || en.isBlank() ? id : en) + " / " + zh;
        }
    }

    /**
     * 配方的一个输入槽。options = 可以放进这个槽的物品（标签已展开，保持标签里的顺序）；tag = 来自标签时的标签名；
     * count = 每做一次要几个；fluid = 这是液体（options 里是液体 ID，count 单位是 mB）；
     * potion = 要求的药水内容（NeoForge 的 neoforge:components 原料、酿造的瓶子槽；说明格式见 {@link ItemComponents}），不要求为 null。
     */
    public record Ingredient(List<String> options, String tag, int count, boolean fluid, String potion) {
        public Ingredient {
            options = copy(options);
        }

        /** 兼容旧调用：不要求药水内容。 */
        public Ingredient(List<String> options, String tag, int count, boolean fluid) {
            this(options, tag, count, fluid, null);
        }

        /** 索引和合成规划用的键：要求药水时是每个选项的变体键（见 {@link GameData#variant}），否则就是 options。 */
        public List<String> keys() {
            if (potion == null) return options;
            List<String> k = new ArrayList<>(options.size());
            for (String o : options) k.add(variant(o, potion));
            return List.copyOf(k);
        }

        public String display() {
            String what = tag != null ? "#" + tag : options.isEmpty() ? "?" : options.size() == 1 ? options.get(0)
                    : String.join("|", options.subList(0, Math.min(3, options.size()))) + (options.size() > 3 ? "|…" : "");
            return (fluid ? "液体 " : "") + what + potionNote(potion) + (fluid ? " " + count + "mB" : "×" + count);
        }
    }

    /**
     * 配方产物。chance &lt; 1 = 概率产出（如 Create 粉碎机的副产物）。fluid = 液体（count 单位 mB）。
     * potion = 产物的药水内容（components 里的 minecraft:potion_contents，见 {@link ItemComponents}；没有为 null）。
     */
    public record Stack(String id, int count, double chance, boolean fluid, String potion) {
        /** 兼容旧调用：没有药水内容。 */
        public Stack(String id, int count, double chance, boolean fluid) {
            this(id, count, chance, fluid, null);
        }

        /** 物品 ID 或变体键（带药水时，见 {@link GameData#variant}）。 */
        public String key() {
            return variant(id, potion);
        }

        /**
         * 这个产物算不算 item：item 是普通物品 ID 时任何药水都算（没指定要哪种）；item 是变体键时药水必须一致。液体不算物品。
         */
        public boolean matches(String item) {
            return !fluid && (id.equals(item) || (potion != null && key().equals(item)));
        }

        public String display() {
            return (fluid ? "液体 " : "") + id + potionNote(potion) + (fluid ? " " + count + "mB" : "×" + count)
                    + (chance < 1 ? "（" + pct(chance) + "）" : "");
        }
    }

    /**
     * 一条配方。type = 序列化器 ID（minecraft:crafting_shaped、create:mixing…）；category = 配方类型 ID（minecraft:crafting…）；
     * station = 在哪做（工作台、熔炉…；模组机器给类型 ID）；shape = 有序合成的摆法（每行一个字符串，字母对应 key）；
     * extra = 原始配方里除了输入输出以外的字段（加工时间、加热要求等，已截短），供模型理解模组机器；
     * droppedSlots = 解析不出、被丢掉的原料槽数（&gt; 0 = inputs 不全，见 {@link #inputsUnknown}）。
     */
    public record Recipe(String id, String type, String category, String station, List<Ingredient> inputs, List<Stack> outputs,
                         List<String> shape, Map<String, String> key, String extra, int droppedSlots) {
        public Recipe {
            inputs = copy(inputs);
            outputs = copy(outputs);
            shape = shape == null ? null : copy(shape);
            key = key == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(key)); // 保持字母顺序，所以不用 Map.copyOf
            droppedSlots = Math.max(0, droppedSlots);
        }

        /** 兼容旧调用：原料全部读到（没有丢槽）。 */
        public Recipe(String id, String type, String category, String station, List<Ingredient> inputs, List<Stack> outputs,
                      List<String> shape, Map<String, String> key, String extra) {
            this(id, type, category, station, inputs, outputs, shape, key, extra, 0);
        }

        /** 做一次能确定得到几个 item（只算 100% 产出的）。item 可以是变体键（只算药水一致的产物，见 {@link Stack#matches}）。 */
        public int surely(String item) {
            int n = 0;
            for (Stack s : outputs) if (s.matches(item) && s.chance() >= 1) n += s.count();
            return n;
        }

        /** 做一次平均得到几个 item（含概率产出）。 */
        public double expected(String item) {
            double n = 0;
            for (Stack s : outputs) if (s.matches(item)) n += s.count() * Math.min(1, s.chance());
            return n;
        }

        public boolean special() {
            return outputs.isEmpty();
        }

        /**
         * 读得到产物、原料却读不全：一个都读不到（染色/烟花这类特殊配方，或模组按方块材质动态决定原料的配方），
         * 或者有原料槽解析不出被丢掉（droppedSlots &gt; 0）。这类配方不能拿来规划路线——缺的那几样不会出现在"还缺"里，
         * 规划会误报"已备齐"。
         */
        public boolean inputsUnknown() {
            return (inputs.isEmpty() || droppedSlots > 0) && !outputs.isEmpty();
        }
    }

    /**
     * 战利品表里的一个物品条目（已展平：alternatives/group/sequence 的子条目都展开了）。
     * chance = 单次抽取时选中这个条目的大致概率（权重/池内候选总权重 × 已知的随机条件概率；-1 = 算不出来）；
     * rolls = 这个池抽几次（"1"、"2-4"）；count = 选中时给几个（"1"、"0-2"）；conditions/functions = 条件和函数的简短说明
     * （如 "需要精准采集"、"玩家击杀"、"抢夺加成"）；alternative = 处在"多选一"结构里（比如精准采集掉自身，否则掉别的）。
     * <p>标签条目（tagEntry = true，item 是标签 ID）：tagExpand = true 时标签里每种物品各是一个独立条目（各带同样权重），
     * chance 是"抽到其中某一种"的概率；tagExpand = false（原版默认）时选中这一条会把标签里每种物品各给一个，chance 是选中这一条的概率。
     * 两种情况下 chance 都等于"某个成员物品出现"的概率，区别只在一次给一种还是全部。
     * <p>potion = 给出的物品带的药水内容（set_potion / set_components 函数，见 {@link ItemComponents}；没有为 null）。
     * via = 经引用的子表到达时的子表链（"minecraft:gameplay/fishing/fish"，多层用" → "连接）；直接写在本表（含内联子表）里的为 null。
     * 经子表到达的条目 chance 已经乘上父池里引用条目的选中概率，rolls 是父池和子表池抽取次数的乘积写法。
     */
    public record Drop(String table, String kind, String item, boolean tagEntry, double chance, String rolls, String count,
                       List<String> conditions, List<String> functions, boolean alternative, boolean tagExpand, String potion, String via) {
        public Drop {
            conditions = copy(conditions);
            functions = copy(functions);
        }

        /** 兼容旧调用：不是展开的标签条目。 */
        public Drop(String table, String kind, String item, boolean tagEntry, double chance, String rolls, String count,
                    List<String> conditions, List<String> functions, boolean alternative) {
            this(table, kind, item, tagEntry, chance, rolls, count, conditions, functions, alternative, false);
        }

        /** 兼容旧调用：没有药水内容、不经子表。 */
        public Drop(String table, String kind, String item, boolean tagEntry, double chance, String rolls, String count,
                    List<String> conditions, List<String> functions, boolean alternative, boolean tagExpand) {
            this(table, kind, item, tagEntry, chance, rolls, count, conditions, functions, alternative, tagExpand, null, null);
        }

        /** 必须用精准采集或剪刀才掉（条件说明里出现"需要精准采集/需要剪刀"，取反的写成"不用…"，不会误判）。 */
        public boolean needsSilkOrShears() {
            for (String c : conditions) if (c.contains("需要精准采集") || c.contains("需要剪刀")) return true;
            return false;
        }
    }

    /**
     * 一张战利品表：kind = block / entity / chest / fishing / archaeology / gift / shearing / other。refs = 本表直接引用的其它表
     * （子表里的物品已按引用条目的概率展开进 drops，见 {@link LootJson}）；notes = 解析说明（引用成环、层数超限、引用的表不存在等）。
     */
    public record LootTable(String id, String kind, List<Drop> drops, List<String> refs, String error, List<String> notes) {
        public LootTable {
            drops = copy(drops);
            refs = copy(refs);
            notes = copy(notes);
        }

        /** 兼容旧调用：没有解析说明。 */
        public LootTable(String id, String kind, List<Drop> drops, List<String> refs, String error) {
            this(id, kind, drops, refs, error, List.of());
        }
    }

    public final Map<String, ItemInfo> items;
    public final List<Recipe> recipes;
    public final Map<String, Recipe> recipeById;
    public final Map<String, List<String>> tags;
    public final Map<String, List<String>> itemTags;
    public final Map<String, LootTable> loot;
    public final Map<String, List<Recipe>> byOutput;
    public final Map<String, List<Recipe>> byInput;
    public final Map<String, List<Drop>> sources;
    /** 战利品表 → 直接引用它的表（子表被谁引用；子表自己的条目概率只是"子表被抽中之后"的概率）。 */
    public final Map<String, List<String>> referencedBy;
    /** 物品 ID → 配方产物/原料/战利品里出现过的药水内容（按字母排序），没有药水变体的物品不在里面。 */
    public final Map<String, List<String>> potionVariants;
    public final long builtAt;
    public final List<String> warnings;

    public GameData(Map<String, ItemInfo> items, List<Recipe> recipes, Map<String, List<String>> tags, Map<String, LootTable> loot,
                    List<String> warnings, long builtAt) {
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        this.recipes = List.copyOf(recipes);
        Map<String, List<String>> tg = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : tags.entrySet()) tg.put(e.getKey(), copy(e.getValue())); // 值也拷贝：平台层传进来的是可变 ArrayList
        this.tags = Collections.unmodifiableMap(tg);
        this.loot = Collections.unmodifiableMap(new LinkedHashMap<>(loot));
        this.warnings = List.copyOf(warnings);
        this.builtAt = builtAt;
        Map<String, Recipe> byId = new LinkedHashMap<>();
        Map<String, List<Recipe>> out = new HashMap<>();
        Map<String, List<Recipe>> in = new HashMap<>();
        Map<String, Set<String>> potions = new HashMap<>();
        // 带药水的产物/原料/掉落同时按物品 ID 和变体键索引：按物品 ID 查能看到所有药水，按变体键只看那一种（合成规划要特定药水时用）
        for (Recipe r : this.recipes) {
            byId.put(r.id(), r);
            Set<String> outs = new LinkedHashSet<>();
            for (Stack s : r.outputs()) {
                outs.add(s.id());
                if (s.potion() != null && !s.fluid()) {
                    outs.add(s.key());
                    potions.computeIfAbsent(s.id(), k -> new TreeSet<>()).add(s.potion());
                }
            }
            for (String o : outs) out.computeIfAbsent(o, k -> new ArrayList<>()).add(r);
            Set<String> ins = new LinkedHashSet<>();
            if (!r.special()) { // 无产物的"配方"（模组的说明类数据）不进反查
                for (Ingredient i : r.inputs()) {
                    ins.addAll(i.options());
                    if (i.potion() != null && !i.fluid()) {
                        ins.addAll(i.keys());
                        for (String o : i.options()) potions.computeIfAbsent(o, k -> new TreeSet<>()).add(i.potion());
                    }
                }
            }
            for (String i : ins) in.computeIfAbsent(i, k -> new ArrayList<>()).add(r);
        }
        this.recipeById = Collections.unmodifiableMap(byId);
        this.byOutput = freeze(out);
        this.byInput = freeze(in);
        Map<String, List<String>> it = new HashMap<>();
        for (Map.Entry<String, List<String>> e : this.tags.entrySet()) {
            for (String item : e.getValue()) it.computeIfAbsent(item, k -> new ArrayList<>()).add(e.getKey());
        }
        this.itemTags = freeze(it);
        Map<String, List<Drop>> src = new HashMap<>();
        Map<String, List<String>> refBy = new HashMap<>();
        for (LootTable t : this.loot.values()) {
            for (Drop d : t.drops()) {
                List<String> members = d.tagEntry() ? this.tags.getOrDefault(d.item(), List.of()) : List.of(d.item());
                for (String item : members) {
                    src.computeIfAbsent(item, k -> new ArrayList<>()).add(d);
                    if (d.potion() != null) {
                        src.computeIfAbsent(variant(item, d.potion()), k -> new ArrayList<>()).add(d);
                        potions.computeIfAbsent(item, k -> new TreeSet<>()).add(d.potion());
                    }
                }
            }
            for (String r : t.refs()) {
                List<String> by = refBy.computeIfAbsent(r, k -> new ArrayList<>());
                if (!by.contains(t.id())) by.add(t.id());
            }
        }
        this.sources = freeze(src);
        this.referencedBy = freeze(refBy);
        Map<String, List<String>> pv = new HashMap<>();
        potions.forEach((k, v) -> pv.put(k, List.copyOf(v)));
        this.potionVariants = Collections.unmodifiableMap(pv);
    }

    private static <T> Map<String, List<T>> freeze(Map<String, List<T>> m) {
        Map<String, List<T>> r = new HashMap<>();
        for (Map.Entry<String, List<T>> e : m.entrySet()) r.put(e.getKey(), copy(e.getValue()));
        return Collections.unmodifiableMap(r);
    }

    /** 不可变副本；null 列表 = 空列表，null 元素丢掉（List.copyOf 遇到 null 会抛 NPE）。已经是 List.of/copyOf 的列表不会再拷一次。 */
    static <T> List<T> copy(List<T> l) {
        if (l == null) return List.of();
        for (T x : l) {
            if (x == null) return l.stream().filter(Objects::nonNull).toList();
        }
        return List.copyOf(l);
    }

    /** 物品名；变体键（带药水）= 物品名 +"（药水 …）"。 */
    public String label(String id) {
        String potion = potionOf(id);
        if (potion != null) return label(baseOf(id)) + potionNote(potion);
        ItemInfo i = items.get(id);
        return i == null ? id : i.label();
    }

    /**
     * 带药水内容的物品变体键："minecraft:tipped_arrow[potion=minecraft:poison]"；potion 为 null/空时就是物品 ID 本身。
     * <p>用途：byOutput / byInput / sources 同时按物品 ID 和变体键索引；合成规划需要特定药水时把变体键当成"物品"。
     * 约束：键只在快照内部和工具输出里用，不是游戏里的 /give 语法；物品 ID 不含 '['，所以 {@link #baseOf}/{@link #potionOf} 按第一个
     * "[potion=" 切分。背包按物品 ID 汇总（分不清药水），所以规划不会从背包里扣带药水的物品。
     */
    public static String variant(String id, String potion) {
        return potion == null || potion.isEmpty() ? id : id + VARIANT_OPEN + potion + "]";
    }

    private static final String VARIANT_OPEN = "[potion=";

    /** 变体键 → 物品 ID；普通物品 ID 原样返回。 */
    public static String baseOf(String key) {
        if (key == null) return null;
        int i = key.indexOf(VARIANT_OPEN);
        return i < 0 ? key : key.substring(0, i);
    }

    /** 变体键里的药水内容；普通物品 ID 返回 null。 */
    public static String potionOf(String key) {
        if (key == null) return null;
        int i = key.indexOf(VARIANT_OPEN);
        if (i < 0 || !key.endsWith("]")) return null;
        return key.substring(i + VARIANT_OPEN.length(), key.length() - 1);
    }

    /** 显示用："（药水 minecraft:poison）"；没有药水返回空串。 */
    static String potionNote(String potion) {
        return potion == null ? "" : "（药水 " + potion + "）";
    }

    /**
     * 物品 + 用户写的药水（"poison"、"minecraft:long_poison"、自定义效果说明的片段）→ 变体键。potion 为空返回 id。
     * 匹配顺序：规范化后的完整药水 ID 是这个物品已知的药水 → 已知药水里路径完全相同的 → 包含查询词的（取最短的，"poison" 先命中
     * minecraft:poison 而不是 long_poison）；都没有时按规范化后的 ID 拼（查不到就是"没有这种药水的配方/来源"，调用方会列出已知药水）。
     */
    public String withPotion(String id, String potion) {
        if (potion == null || potion.isBlank()) return id;
        String raw = potion.trim().toLowerCase(Locale.ROOT);
        String q = norm(raw);
        List<String> known = potionVariants.getOrDefault(id, List.of());
        if (known.contains(q)) return variant(id, q);
        String path = q.substring(q.indexOf(':') + 1);
        String best = null;
        for (String k : known) {
            if (k.equals(raw) || k.substring(k.indexOf(':') + 1).equals(path)) return variant(id, k);
            if (k.toLowerCase(Locale.ROOT).contains(raw) && (best == null || k.length() < best.length())) best = k;
        }
        return variant(id, best != null ? best : q);
    }

    /** "iron_ingot" → "minecraft:iron_ingot"；已带命名空间的原样返回；去掉开头的 #。 */
    public static String norm(String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) s = s.substring(1);
        return s.isEmpty() || s.contains(":") ? s : "minecraft:" + s;
    }

    /**
     * 按名字或 ID 找物品：完全匹配 ID → 完全匹配名字 → ID/英文名/中文名包含查询词。返回按相关度排序的前 limit 个。
     * 查询词里的空格会被忽略，以便 "iron ingot" 能命中 iron_ingot。
     * <p>带命名空间的查询（create:andesite）：命名空间只用来筛模组（必须完全相同），冒号后面的部分按上面的规则匹配路径和名字。
     * 原因：包含匹配只比路径（见下），整串 "create:andesite" 永远不会出现在路径里。只给命名空间（"create:"）时列出这个模组的物品。
     */
    public List<ItemInfo> search(String query, int limit) {
        String raw = query == null ? "" : query.trim();
        String ns = null;
        int colon = raw.indexOf(':');
        if (colon >= 0) {
            ns = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            raw = raw.substring(colon + 1).trim();
            if (ns.isEmpty()) ns = null; // ":andesite" 当作不限模组
        }
        String q = raw.toLowerCase(Locale.ROOT);
        if (q.isEmpty() && ns == null) return List.of();
        String qn = q.replace(' ', '_');
        String qs = q.replace(" ", "").replace("_", "");
        List<ItemInfo> exact = new ArrayList<>();
        List<ItemInfo> name = new ArrayList<>();
        List<ItemInfo> starts = new ArrayList<>();
        List<ItemInfo> contains = new ArrayList<>();
        for (ItemInfo i : items.values()) {
            if (ns != null && !i.mod().equals(ns)) continue;
            String path = i.id().substring(i.id().indexOf(':') + 1);
            String en = i.en() == null ? "" : i.en().toLowerCase(Locale.ROOT);
            String zh = i.zh() == null ? "" : i.zh();
            if (q.isEmpty()) starts.add(i); // 只给了命名空间
            else if (path.equals(qn)) exact.add(i);
            else if (en.equals(q) || zh.equals(raw)) name.add(i);
            else if (path.startsWith(qn) || en.startsWith(q) || (!zh.isEmpty() && zh.startsWith(raw))) starts.add(i);
            // 包含匹配只比路径部分（否则搜 waystone 会把 waystones 模组的所有物品都排进来）
            else if (path.contains(qn) || en.replace(" ", "").contains(qs) || (!zh.isEmpty() && zh.contains(raw))
                    || path.replace("_", "").contains(qs)) contains.add(i);
        }
        List<ItemInfo> all = new ArrayList<>();
        // 同一档内：原版优先、ID 短的优先（iron_ingot 排在 iron_ingot_from_xxx 前面）
        for (List<ItemInfo> l : List.of(exact, name, starts, contains)) {
            l.sort((a, b) -> {
                int va = a.mod().equals("minecraft") ? 0 : 1, vb = b.mod().equals("minecraft") ? 0 : 1;
                if (va != vb) return va - vb;
                if (a.id().length() != b.id().length()) return a.id().length() - b.id().length();
                return a.id().compareTo(b.id());
            });
            all.addAll(l);
        }
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** 把用户写的物品（ID、去掉命名空间的 ID、准确名字）解析成物品 ID；找不到返回 null。 */
    public String resolveItem(String query) {
        if (query == null || query.isBlank()) return null;
        String n = norm(query);
        if (items.containsKey(n)) return n;
        List<ItemInfo> hits = search(query, 2);
        if (hits.isEmpty()) return null;
        ItemInfo h = hits.get(0);
        // 带命名空间时 search 已按命名空间筛过，这里只比冒号后面的部分
        String raw = query.trim().substring(query.trim().indexOf(':') + 1).trim();
        String q = raw.toLowerCase(Locale.ROOT);
        String path = h.id().substring(h.id().indexOf(':') + 1);
        boolean strong = !q.isEmpty() && (path.equals(q.replace(' ', '_')) || (h.en() != null && h.en().equalsIgnoreCase(raw))
                || (h.zh() != null && h.zh().equals(raw)));
        return strong || hits.size() == 1 ? h.id() : null;
    }

    static String pct(double p) {
        if (p >= 0.995) return "100%";
        if (p >= 0.1) return Math.round(p * 100) + "%";
        if (p >= 0.01) return String.format(Locale.ROOT, "%.1f%%", p * 100);
        return String.format(Locale.ROOT, "%.2f%%", p * 100);
    }
}
