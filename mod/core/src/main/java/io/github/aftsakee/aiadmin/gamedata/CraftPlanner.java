package io.github.aftsakee.aiadmin.gamedata;

import io.github.aftsakee.aiadmin.gamedata.GameData.Drop;
import io.github.aftsakee.aiadmin.gamedata.GameData.Ingredient;
import io.github.aftsakee.aiadmin.gamedata.GameData.Recipe;
import io.github.aftsakee.aiadmin.gamedata.GameData.Stack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合成链路规划：给一个目标物品和数量，再给玩家手上有什么，展开整条合成树，得到
 * "按顺序做哪些配方、各做几次、用掉背包里的什么、还缺哪些原料"。
 * <p>算法：深度优先、先用库存再展开配方；每个物品选一条配方（{@link #choose}），子原料做完再记这一步（后序 = 可执行顺序），
 * 同一配方多处用到时合并次数。配方的多余产物（一次出 4 个木板只用 3 个）回到库存，后面的需求可以接着用。
 * <p>药水变体：目标或原料要求特定药水时用变体键（{@link GameData#variant}，如 minecraft:lingering_potion[potion=minecraft:poison]）当物品，
 * 只选产物药水一致的配方（{@link GameData.Stack#matches}）；普通物品 ID 表示"任意一种都行"。背包按物品 ID 汇总、分不清药水，
 * 所以带药水的原料不会从背包里扣（偏保守：可能把背包里已有的药水也列进"还缺"）。
 * <p>边界与约束（避免死循环和"铁锭 ← 铁块 ← 铁锭"这类压缩配方）：
 * <ul>
 *   <li>正在展开的物品放进 visiting，任何选项全在 visiting 里的原料视为成环，这条配方不选；</li>
 *   <li>"可逆配方"（原料本身能由目标物品做出来，典型是方块/粒互转）排在后面，除非库存里的原料已经够用；</li>
 *   <li>层数上限 {@link Options#maxDepth}，展开节点上限 {@link #NODE_LIMIT}，超出的部分记为"还缺"并在 notes 里说明截断；</li>
 *   <li>按 100% 产出计算；只有概率产出的配方（如粉碎副产物）按期望值估算次数并在 notes 里提示（打分和展开用同一个 {@link #craftsFor}）；</li>
 *   <li>数量一律用 long 计算并封顶到 {@link #CAP}，封顶时 notes 里说明（概率极低的配方会把次数放大到超过 int）。</li>
 * </ul>
 * 纯计算、不改 GameData，可在任何线程调用；返回的 Plan/Step 不可变。
 */
public final class CraftPlanner {
    static final int NODE_LIMIT = 4000;
    /**
     * 数量上限。原因：期望 0.001 个/次的概率配方、或很深的链路会把次数/数量放大到超过 int，溢出成负数后"还缺"变负、库存凭空变多。
     * 边界：内部所有次数/数量用 long 计算，每次乘/加之后封顶到这里；两个封顶值相加或与 int 相乘仍在 long 范围内。
     * 取 int 上限的一半，调用方（显示、JSON）把两个封顶值相加也不会溢出 int。约束：封顶后数字只供参考，plan 会在 notes 里说明。
     */
    static final int CAP = Integer.MAX_VALUE / 2;
    /**
     * 生物掉落"算得上常见"的单次概率下限（commonDrop / hardToGet 共用）。
     * 来由：原版常见材料的生物掉落（线、骨头、火药、皮革、腐肉…）都是每次必掉 0-2 个这类（chance = 100%），
     * 稀有掉落（僵尸掉铁锭 2.5%/3、兔子脚 10%）远低于此；0.3 落在两者之间，把"去打怪就能拿到"和"碰运气"分开。
     * 边界：-1（算不出）按常见处理——宁可建议"去打怪"，也不要把普通掉落误判成难拿。
     */
    static final double COMMON_ENTITY_DROP = 0.3;

    /** forceRecipe：物品 → 指定用哪条配方；avoidCategories：不用这些配方类型（如 "minecraft:blasting"）；allowModMachines = false 时只用原版工作站。 */
    public record Options(int maxDepth, Set<String> avoidCategories, Map<String, String> forceRecipe, boolean allowModMachines) {
        public static Options defaults() {
            return new Options(12, Set.of(), Map.of(), true);
        }
    }

    /** 一步：在 recipe 的工作站做 crafts 次，主要为了得到 item（同一配方多处用到时已合并）。不可变。 */
    public static final class Step {
        public final Recipe recipe;
        public final String item;
        public final int crafts;

        Step(Recipe recipe, String item, int crafts) {
            this.recipe = recipe;
            this.item = item;
            this.crafts = crafts;
        }
    }

    /** reason：no_recipe（只能采集/打怪/开箱获得）、gather（有配方但直接采集更合理）、cycle（只剩成环的配方）、depth（层数太深）、truncated（规划太大）、unknown_tag（标签里没有物品）、unknown_inputs（配方原料读不全）。 */
    public record Missing(String item, int count, String reason) {}

    /** 规划结果。紧凑构造器拷贝所有集合（保持顺序），planner 内部的可变状态不会泄露出去。 */
    public record Plan(String target, int count, int haveAlready, List<Step> steps, List<Missing> missing, Map<String, Integer> fluids,
                       Map<String, Integer> usedFromInventory, Map<String, Integer> leftovers, List<String> notes) {
        public Plan {
            steps = List.copyOf(steps);
            missing = List.copyOf(missing);
            fluids = frozen(fluids);
            usedFromInventory = frozen(usedFromInventory);
            leftovers = frozen(leftovers);
            notes = List.copyOf(notes);
        }

        public boolean ready() {
            return missing.isEmpty() && fluids.isEmpty();
        }

        private static Map<String, Integer> frozen(Map<String, Integer> m) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(m)); // 不用 Map.copyOf：要保持插入顺序
        }
    }

    /** 合并中的一步（内部可变，plan 结束时转成不可变的 Step）。 */
    private static final class StepAcc {
        final Recipe recipe;
        final String item;
        long crafts;

        StepAcc(Recipe recipe, String item) {
            this.recipe = recipe;
            this.item = item;
        }
    }

    /**
     * 一条候选配方的打分，字段名即含义；排序规则见 {@link #ORDER}。
     * gatherOnly 不参与排序，只用于"直接采集是否更合理"的判定（见 choose）。
     */
    private record Score(boolean haveAll, boolean reversible, int hardInputs, int stationRank, int partlyHeld, int inputKinds,
                         boolean modded, boolean probabilistic, boolean gatherOnly) {}

    /** 候选配方的优先级（越靠前越重要）；全部相同时按配方 ID 排，保证结果确定。 */
    private static final Comparator<Score> ORDER = Comparator
            .comparing((Score s) -> !s.haveAll())                                  // 库存凑得齐的优先
            .thenComparing(Score::reversible)                                      // 不可逆的优先（避开方块↔锭互转）
            .thenComparingInt(Score::hardInputs)                                   // 难拿原料少的优先
            .thenComparingInt(Score::stationRank)                                  // 工作台 < 熔炉 < … < 模组机器
            .thenComparing(Comparator.comparingInt(Score::partlyHeld).reversed())  // 库存里有一部分的原料多的优先
            .thenComparingInt(Score::inputKinds)                                   // 原料种类少的优先
            .thenComparing(Score::modded)                                          // 原版配方优先
            .thenComparing(Score::probabilistic);                                  // 同分时确定产出优先

    /**
     * pick 的候选物品排序键。字典序：库存够 &gt; 库存有一部分 &gt; 没有；同档里好拿的优先；再按持有量多的优先、原版优先、标签里靠前的优先。
     * 原因：旧实现用相加的整数分档（3_000_000 / 2_000_000 + 持有量 / 500_000 / 1_000），持有量很大时低档会加过高档
     * （"有一部分 + 好拿"超过"库存够"）；改成显式比较后各档严格分开，没有权重要维护。
     */
    private record PickKey(int held, boolean easy, long amount, boolean vanilla, int index) {}

    private static final Comparator<PickKey> PICK_ORDER = Comparator
            .comparingInt(PickKey::held).reversed()
            .thenComparing(PickKey::easy, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingLong(PickKey::amount).reversed())
            .thenComparing(PickKey::vanilla, Comparator.reverseOrder())
            .thenComparingInt(PickKey::index);

    private final GameData d;
    private final Options o;
    private final Map<String, Long> inv = new HashMap<>();
    private final Map<String, StepAcc> steps = new LinkedHashMap<>();
    private final Map<String, Long> missing = new LinkedHashMap<>();
    private final Map<String, String> missingWhy = new HashMap<>();
    private final Map<String, Long> fluids = new LinkedHashMap<>();
    private final Set<String> visiting = new HashSet<>();
    /** 直接采集比走配方更合理的物品（见 choose）。 */
    private final Set<String> gather = new HashSet<>();
    /** hasUsableRecipe 的缓存：hardToGet 在打分和 pick 里对同一物品反复调用。只在本次规划内有效（GameData 不变）。 */
    private final Map<String, Boolean> usable = new HashMap<>();
    private final List<String> notes = new ArrayList<>();
    private int nodes;
    private boolean truncated;
    private boolean capped;

    private CraftPlanner(GameData d, Map<String, Integer> inventory, Options o) {
        this.d = d;
        this.o = o;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) inv.put(e.getKey(), (long) e.getValue());
        }
    }

    public static Plan plan(GameData d, String target, int count, Map<String, Integer> inventory, Options o) {
        CraftPlanner p = new CraftPlanner(d, inventory, o == null ? Options.defaults() : o);
        Integer hv = inventory.get(target);
        int have = hv == null ? 0 : Math.max(0, hv);
        p.need(target, count, 0);
        if (p.truncated) p.notes.add("合成树太大，只展开了前 " + NODE_LIMIT + " 个节点，剩下的记在\"还缺\"里");
        Map<String, Integer> used = new LinkedHashMap<>();
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            long after = p.inv.getOrDefault(e.getKey(), 0L);
            if (after < e.getValue()) used.put(e.getKey(), p.toInt(e.getValue() - after));
        }
        for (Map.Entry<String, Long> e : p.inv.entrySet()) {
            Integer before = inventory.get(e.getKey());
            long b = before == null || before < 0 ? 0 : before;
            if (e.getValue() > b) left.put(e.getKey(), p.toInt(e.getValue() - b));
        }
        List<Step> stepList = new ArrayList<>();
        for (StepAcc a : p.steps.values()) stepList.add(new Step(a.recipe, a.item, p.toInt(a.crafts)));
        List<Missing> miss = new ArrayList<>();
        for (Map.Entry<String, Long> e : p.missing.entrySet()) miss.add(new Missing(e.getKey(), p.toInt(e.getValue()), p.missingWhy.get(e.getKey())));
        Map<String, Integer> fl = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : p.fluids.entrySet()) fl.put(e.getKey(), p.toInt(e.getValue()));
        if (p.capped) p.notes.add("数量太大，部分次数/数量已封顶为 " + CAP + "，只能作参考");
        return new Plan(target, count, have, stepList, miss, fl, used, left, p.notes);
    }

    /** 封顶：超过 CAP 的值记一次 capped，返回 CAP。所有 long 中间结果存回状态前都过这里。 */
    private long cap(long v) {
        if (v > CAP) {
            capped = true;
            return CAP;
        }
        return v;
    }

    private int toInt(long v) {
        return (int) cap(Math.max(0, v));
    }

    private void note(String s) {
        if (!notes.contains(s)) notes.add(s);
    }

    private void miss(String item, long n, String why) {
        missing.merge(item, cap(n), (a, b) -> cap(a + b));
        missingWhy.putIfAbsent(item, why);
    }

    private void need(String item, long n, int depth) {
        if (n <= 0) return;
        if (++nodes > NODE_LIMIT) {
            truncated = true;
            miss(item, n, "truncated");
            return;
        }
        long have = inv.getOrDefault(item, 0L);
        long take = Math.min(have, n);
        if (take > 0) {
            inv.put(item, have - take);
            n -= take;
        }
        if (n == 0) return;
        if (depth >= o.maxDepth()) {
            miss(item, n, "depth");
            return;
        }
        Recipe r = choose(item, n);
        if (r == null) {
            boolean hadRecipes = d.byOutput.containsKey(item);
            boolean onlyUnknown = hadRecipes && !hasUsableRecipe(item);
            miss(item, n, gather.contains(item) ? "gather" : onlyUnknown ? "unknown_inputs" : hadRecipes ? "cycle" : "no_recipe");
            return;
        }
        long crafts = cap(craftsFor(r, item, n));
        if (r.surely(item) <= 0) note(d.label(item) + " 只能概率产出（" + r.id() + "），按平均值估算做 " + crafts + " 次");
        visiting.add(item);
        for (Ingredient ing : r.inputs()) {
            long amt = cap((long) ing.count() * crafts); // crafts ≤ CAP、count ≤ int 上限：乘积在 long 范围内
            if (ing.fluid()) {
                fluids.merge(ing.tag() != null ? "#" + ing.tag() : ing.options().isEmpty() ? "?" : ing.options().get(0), amt, (a, b) -> cap(a + b));
                continue;
            }
            String pick = pick(ing, amt);
            if (pick == null) {
                miss(ing.tag() != null ? "#" + ing.tag() : "?", amt, ing.options().isEmpty() ? "unknown_tag" : "cycle");
                continue;
            }
            need(pick, amt, depth + 1);
        }
        visiting.remove(item);
        StepAcc acc = steps.computeIfAbsent(r.id(), k -> new StepAcc(r, item));
        acc.crafts = cap(acc.crafts + crafts);
        // 产物回库存：目标物品多做出来的部分、以及副产物（只算 100% 产出），后面的需求可以接着用。
        // 带药水的产物按变体键入库：之后要同一种药水时能接着用；要"任意一种"（普通物品 ID）时不会去用它（少用一点剩余，不会用错药水）
        for (Stack s : r.outputs()) {
            if (s.fluid() || s.chance() < 1) continue;
            long produced = cap((long) s.count() * crafts) - (s.matches(item) ? n : 0);
            if (produced > 0) inv.merge(s.key(), produced, (a, b) -> cap(a + b));
        }
    }

    /**
     * 要得到 n 个 item 需要做几次。打分（choose）和展开（need）必须用同一个函数：否则打分以为库存够做（haveAll），
     * 展开时却按另一种算法多做几次，规划会选中实际凑不齐的配方。
     * <p>有确定产出时按确定产出向上取整；只有概率产出时按期望值向上取整（减 1e-9 容差，避免 3 / 0.3 = 10.000000000000002 多算一次）。
     * 结果可能超过 CAP，由调用方封顶；期望 ≤ 0 的配方不可用（choose 已滤掉），返回 Long.MAX_VALUE 只是防御。
     */
    static long craftsFor(Recipe r, String item, long n) {
        if (n <= 0) return 0;
        int sure = r.surely(item);
        if (sure > 0) return (n + sure - 1) / sure;
        double e = r.expected(item);
        if (!(e > 0)) return Long.MAX_VALUE;
        return (long) Math.max(1, Math.ceil(n / e - 1e-9)); // double → long 超范围时 Java 饱和到 Long.MAX_VALUE
    }

    /** 配方能不能用来规划：有产物、原料读得全、做一次确实能得到这个物品（choose / gatherOnlyInputs / hardToGet 共用同一判断）。 */
    private static boolean usableFor(Recipe r, String item) {
        return !r.special() && !r.inputsUnknown() && r.expected(item) > 0;
    }

    /** 这个物品有没有至少一条可用来规划的配方（见 {@link #usableFor}）。 */
    boolean hasUsableRecipe(String x) {
        return usable.computeIfAbsent(x, k -> {
            for (Recipe r : d.byOutput.getOrDefault(k, List.of())) if (usableFor(r, k)) return true;
            return false;
        });
    }

    /** 给一个物品挑一条配方；没有可用的返回 null。 */
    Recipe choose(String item, long n) {
        List<Recipe> all = d.byOutput.getOrDefault(item, List.of());
        String forced = o.forceRecipe().get(item);
        List<Recipe> cands = new ArrayList<>();
        for (Recipe r : all) {
            if (!usableFor(r, item)) continue; // 读不到原料的配方不能用来规划（否则会显示"已备齐"）
            if (forced != null && !r.id().equals(forced)) continue;
            if (o.avoidCategories().contains(r.category())) continue;
            if (!o.allowModMachines() && !RecipeJson.isVanillaStation(r.category())) continue;
            if (cyclic(r, item)) continue;
            cands.add(r);
        }
        if (cands.isEmpty()) return null;
        Map<Recipe, Score> score = new HashMap<>();
        for (Recipe r : cands) score.put(r, score(r, item, n));
        // 能直接挖到/打到的东西（煤、粗铁、线、皮革…），除非库存已经凑得齐，只有"像样"的配方才走：不可逆、原料好拿、
        // 原版工作站、确定产出、而且原料不全是"也得去采集"的东西——否则（拆鞍取线、兔子皮拼皮革、烧要精准采集的矿石）记为"直接采集"
        if (forced == null && commonDrop(item)) {
            boolean good = false;
            for (Recipe r : cands) {
                Score sc = score.get(r);
                if (sc.haveAll() || (!sc.reversible() && sc.hardInputs() == 0 && RecipeJson.isVanillaStation(r.category())
                        && !sc.gatherOnly() && !sc.probabilistic())) {
                    good = true;
                    break;
                }
            }
            if (!good) {
                gather.add(item);
                return null;
            }
        }
        cands.sort(Comparator.comparing((Recipe r) -> score.get(r), ORDER).thenComparing(Recipe::id));
        return cands.get(0);
    }

    private Score score(Recipe r, String item, long n) {
        long crafts = craftsFor(r, item, n);
        boolean haveAll = true;
        int havePart = 0, hard = 0;
        for (Ingredient ing : r.inputs()) {
            if (ing.fluid()) {
                haveAll = false;
                continue;
            }
            long needN = crafts >= Long.MAX_VALUE / Math.max(1, ing.count()) ? Long.MAX_VALUE : (long) ing.count() * crafts;
            boolean full = false, part = false, easy = false;
            for (String x : ing.keys()) {
                long h = inv.getOrDefault(x, 0L);
                if (h >= needN) full = true;
                if (h > 0) part = true;
                if (!hardToGet(x)) easy = true;
            }
            if (!full) haveAll = false;
            if (part) havePart++;
            if (!easy) hard++;
        }
        return new Score(haveAll, reversible(r, item), hard, RecipeJson.stationRank(r.category()), havePart, r.inputs().size(),
                !r.id().startsWith("minecraft:"), r.surely(item) <= 0, gatherOnlyInputs(r));
    }

    /** 配方的每个原料都没有可用配方、只能采集/掉落获得（用它做 = 换一样东西去采集，不如直接采集目标）。 */
    private boolean gatherOnlyInputs(Recipe r) {
        if (r.inputs().isEmpty()) return false;
        for (Ingredient ing : r.inputs()) {
            if (ing.fluid()) return false;
            for (String x : ing.keys()) if (hasUsableRecipe(x)) return false;
        }
        return true;
    }

    /** 某个原料的所有选项都在 visiting 里（或就是目标自己）→ 这条配方会成环。 */
    private boolean cyclic(Recipe r, String item) {
        for (Ingredient ing : r.inputs()) {
            if (ing.fluid() || ing.options().isEmpty()) continue;
            boolean allBlocked = true;
            for (String x : ing.keys()) {
                if (!x.equals(item) && !visiting.contains(x)) {
                    allBlocked = false;
                    break;
                }
            }
            if (allBlocked) return true;
        }
        return false;
    }

    /** 可逆配方：某个原料（任一选项）本身能用目标物品做出来——方块↔锭、锭↔粒这类压缩/拆分。 */
    private boolean reversible(Recipe r, String item) {
        for (Ingredient ing : r.inputs()) {
            if (ing.fluid()) continue;
            int checked = 0;
            for (String x : ing.keys()) {
                if (++checked > 4) break;
                for (Recipe q : d.byOutput.getOrDefault(x, List.of())) {
                    for (Ingredient qi : q.inputs()) if (qi.keys().contains(item)) return true;
                }
            }
        }
        return false;
    }

    /** 生物掉落的单次概率算得上常见（≥ {@link #COMMON_ENTITY_DROP}，或算不出）。 */
    private static boolean commonEntityChance(Drop s) {
        return s.chance() < 0 || s.chance() >= COMMON_ENTITY_DROP;
    }

    /**
     * 常见掉落：挖某种方块（不是这个物品自己的方块形态、不要求精准采集/剪刀）就会掉，或者生物掉落概率不低（见 {@link #COMMON_ENTITY_DROP}）。
     * 用来判断"直接采集"是否比可逆/难拿原料的配方更合理。
     */
    boolean commonDrop(String x) {
        String path = x.substring(x.indexOf(':') + 1);
        for (Drop s : d.sources.getOrDefault(x, List.of())) {
            if (s.needsSilkOrShears()) continue;
            String t = s.table().substring(s.table().indexOf(':') + 1);
            if (s.kind().equals("block") && !t.equals("blocks/" + path)) return true;
            if (s.kind().equals("entity") && commonEntityChance(s)) return true;
        }
        return false;
    }

    /**
     * 难拿：没有可用配方，而且没有"实际可行"的来源——实际可行 = 挖方块就掉（不要求精准采集/剪刀；方块掉自己也算，比如原木），
     * 或生物掉落概率不低（见 {@link #COMMON_ENTITY_DROP}）。只能从宝箱、考古、模组的特殊战利品表（例如 MineColonies 矿工的"幸运矿石"）、
     * 或者只能精准采集拿到的，都算难拿——否则"铁矿石"会因为殖民地矿工偶尔挖到而被当成和"粗铁"一样好拿。
     * "有配方"与 choose 用同一判断（{@link #hasUsableRecipe}）：只有读不全原料的配方时不算有配方。
     */
    boolean hardToGet(String x) {
        if (hasUsableRecipe(x)) return false;
        for (Drop s : d.sources.getOrDefault(x, List.of())) {
            if (s.needsSilkOrShears()) continue;
            if (s.kind().equals("block")) return false;
            if (s.kind().equals("entity") && commonEntityChance(s)) return false;
        }
        return true;
    }

    /** 给一个原料槽挑具体放哪个物品（排序见 {@link #PICK_ORDER}）。全成环返回 null。 */
    private String pick(Ingredient ing, long amount) {
        String best = null;
        PickKey bestKey = null;
        int i = 0;
        for (String x : ing.keys()) {
            i++;
            if (visiting.contains(x)) continue;
            long h = inv.getOrDefault(x, 0L);
            PickKey k = new PickKey(h >= amount ? 2 : h > 0 ? 1 : 0, !hardToGet(x), h >= amount ? 0 : h, x.startsWith("minecraft:"), i);
            if (bestKey == null || PICK_ORDER.compare(k, bestKey) < 0) {
                bestKey = k;
                best = x;
            }
        }
        return best;
    }
}
