package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 玩家行为追踪：让代理知道"谁在哪、在做什么、背包怎么变"，而不只是某一瞬间的快照或游戏日志。
 * <ul>
 *   <li>采样：平台层每隔几秒交来每名在线玩家的位置、维度、群系、手持物、血量饥饿、坐骑、是否睡觉（保留 history_minutes）。</li>
 *   <li>事件计数：挖/放方块、击杀、受伤、合成、拾取、交互方块/实体、用物品、从容器拿/放等按"每分钟一桶"累计——挖矿时每秒一个方块，
 *       逐条存会很快撑爆，按桶计数内存固定；挖/放方块另按区块记"在哪一带"（{@link #AREA_KINDS}）。</li>
 *   <li>值得单独记的事件（死亡、换维度、交互了哪个方块/实体、容器里拿走放入了什么、交易、贵重矿石、危险物品…）进一个有上限的环形缓冲，
 *       带时间和位置；短时间内同一地点的同一件事合并成一条"×N"，连点同一个按钮/拉杆不会把缓冲挤满。</li>
 *   <li>背包：每分钟一份物品计数快照，用来算最近 N 分钟多了什么、少了什么。</li>
 *   <li>{@link #guess}：按最近 5 分钟的数据粗略判断在做什么（挖矿/建造/战斗/种田/砍树/赶路/整理箱子/挂机…），原始数据也一起给模型，由它自己判断。</li>
 * </ul>
 * 线程：事件和采样都在服务端主线程写入，工具在代理线程读取，所有方法加同一把锁；每个操作都是 O(采样数) 的内存运算。
 * 玩家下线后数据保留到 history_minutes 过期，方便事后回答"他刚才在干嘛"。
 */
public final class PlayerTracker {
    public record Sample(long time, String dim, double x, double y, double z, String biome, float health, float maxHealth, int food,
                         String held, String gameMode, String vehicle, boolean sleeping) {}

    /** times：合并后的次数；time 是最后一次发生的时间，first 是第一次。 */
    public record Notable(long first, long time, String kind, String detail, String where, int times) {}

    private static final class Bucket {
        final long minute;
        final Map<String, Integer> counts = new HashMap<>();
        /** "kind|维度|区块X|区块Z" → {次数, Y 之和}：只给 {@link #AREA_KINDS}，用来回答"在哪一带挖/建"。 */
        final Map<String, long[]> areas = new HashMap<>();

        Bucket(long minute) {
            this.minute = minute;
        }
    }

    private static final class P {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        final ArrayDeque<Bucket> buckets = new ArrayDeque<>();
        final ArrayDeque<Notable> notable = new ArrayDeque<>();
        final ArrayDeque<Map.Entry<Long, Map<String, Integer>>> inventories = new ArrayDeque<>();
        long joined;
        long left;
    }

    /** 计数类事件。 */
    public static final List<String> KINDS = List.of("break", "place", "kill", "hurt", "craft", "pickup", "use", "interact_entity",
            "use_item", "take", "put", "drop");
    /** 记录"在哪一带"的计数类事件。 */
    static final Set<String> AREA_KINDS = Set.of("break", "place");
    /** 合并窗口：同一玩家、同一 kind+detail、同一维度且各轴相距不超过 {@link #MERGE_DISTANCE} 格的事件在这段时间内合并。 */
    static final long MERGE_MS = 120_000L;
    static final int MERGE_DISTANCE = 16;
    static final Set<String> CROPS = Set.of("wheat", "carrots", "potatoes", "beetroots", "sugar_cane", "melon", "pumpkin", "nether_wart",
            "cocoa", "sweet_berry_bush", "bamboo", "cactus", "farmland");
    private static final int NOTABLE_MAX = 600;

    private final Map<String, P> players = new LinkedHashMap<>();
    private final long historyMs;

    public PlayerTracker(int historyMinutes) {
        this.historyMs = historyMinutes * 60_000L;
    }

    /**
     * 玩家名不分大小写（MC 名字本身不分大小写，AI 查询时常写成小写）：第一次见到的写法作为显示名和键，之后任何大小写都归到它。
     * 不用 UUID：离线服的 UUID 由名字算出，和名字一样稳定（与 GoalStore 同一约定）。
     */
    private final Map<String, String> canon = new java.util.HashMap<>();

    private String canon(String name) {
        return canon.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), k -> name);
    }

    private String lookup(String name) {
        return name == null ? null : canon.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), name);
    }

    private P p(String name) {
        return players.computeIfAbsent(canon(name), k -> new P());
    }

    // ———————————————————— 写入（服务端主线程） ————————————————————

    public synchronized void join(String name, long now) {
        P p = p(name);
        p.joined = now;
        p.left = 0;
        addNotable(p, new Notable(now, now, "join", "进入服务器", null, 1));
    }

    public synchronized void leave(String name, long now) {
        P p = players.get(lookup(name));
        if (p == null) return;
        p.left = now;
        addNotable(p, new Notable(now, now, "leave", "离开服务器", lastWhere(p), 1));
    }

    public synchronized void sample(String name, Sample s) {
        P p = p(name);
        if (p.joined == 0) p.joined = s.time();
        p.samples.addLast(s);
        prune(p, s.time());
    }

    /** 计数事件：kind ∈ {@link #KINDS}，detail 一般是方块/物品/实体 id。 */
    public synchronized void count(String name, String kind, String detail, long now) {
        count(name, kind, detail, 1, null, 0, 0, 0, now);
    }

    /**
     * 计数事件，n 次；dim 不为 null 时（挖/放方块）同时按区块记位置。
     * 每桶的键数有上限：异常情况（例如大量不同的模组方块）宁可丢掉这一分钟的数据，也不让内存无限涨。
     */
    public synchronized void count(String name, String kind, String detail, int n, String dim, int x, int y, int z, long now) {
        P p = p(name);
        long minute = now / 60_000L;
        Bucket b = p.buckets.peekLast();
        if (b == null || b.minute != minute) {
            b = new Bucket(minute);
            p.buckets.addLast(b);
        }
        b.counts.merge(kind + "|" + detail, n, Integer::sum);
        if (b.counts.size() > 2000) b.counts.clear();
        if (dim != null && AREA_KINDS.contains(kind)) {
            long[] a = b.areas.computeIfAbsent(kind + "|" + dim + "|" + (x >> 4) + "|" + (z >> 4), k -> new long[2]);
            a[0] += n;
            a[1] += (long) y * n;
            if (b.areas.size() > 500) b.areas.clear();
        }
        prune(p, now);
    }

    /** 值得单独记的事件（死亡、换维度、交互、容器存取、交易等），带位置；短时间内同一件事会合并成"×N"。 */
    public synchronized void notable(String name, String kind, String detail, String where, long now) {
        P p = p(name);
        String w = where != null ? where : lastWhere(p);
        // 只回看最近几条：合并是为了吸收连点（按钮、拉杆、连续交易、一条矿脉），不是做全局去重
        int look = 0;
        for (var it = p.notable.descendingIterator(); it.hasNext() && look < 6; look++) {
            Notable n = it.next();
            if (now - n.time() > MERGE_MS) break;
            if (n.kind().equals(kind) && n.detail().equals(detail) && near(n.where(), w)) {
                it.remove();
                addNotable(p, new Notable(n.first(), now, kind, detail, n.where(), n.times() + 1));
                return;
            }
        }
        addNotable(p, new Notable(now, now, kind, detail, w, 1));
    }

    /** 两个 "维度 x y z" 是否同维度且各轴相距不超过 {@link #MERGE_DISTANCE}；任一为空时只有都为空才算相同。 */
    public static boolean near(String a, String b) {
        if (a == null || b == null) return a == b;
        String[] x = a.split(" "), y = b.split(" ");
        if (x.length != 4 || y.length != 4 || !x[0].equals(y[0])) return false;
        try {
            for (int i = 1; i < 4; i++) if (Math.abs(Integer.parseInt(x[i]) - Integer.parseInt(y[i])) > MERGE_DISTANCE) return false;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public synchronized void inventory(String name, Map<String, Integer> counts, long now) {
        P p = p(name);
        p.inventories.addLast(Map.entry(now, Map.copyOf(counts)));
        prune(p, now);
    }

    private void addNotable(P p, Notable n) {
        p.notable.addLast(n);
        while (p.notable.size() > NOTABLE_MAX) p.notable.pollFirst();
    }

    private void prune(P p, long now) {
        while (!p.samples.isEmpty() && now - p.samples.peekFirst().time() > historyMs) p.samples.pollFirst();
        while (!p.buckets.isEmpty() && now / 60_000L - p.buckets.peekFirst().minute > historyMs / 60_000L) p.buckets.pollFirst();
        while (!p.inventories.isEmpty() && now - p.inventories.peekFirst().getKey() > historyMs) p.inventories.pollFirst();
        while (!p.notable.isEmpty() && now - p.notable.peekFirst().time() > historyMs) p.notable.pollFirst();
    }

    /** 清掉早已下线、数据也已过期的玩家。 */
    public synchronized void purge(long now) {
        players.entrySet().removeIf(e -> e.getValue().left > 0 && now - e.getValue().left > historyMs);
    }

    // ———————————————————— 读取（代理线程） ————————————————————

    public synchronized boolean known(String name) {
        return players.containsKey(lookup(name));
    }

    /** 一句话：在做什么（最近 5 分钟）。 */
    public synchronized String guess(String name, long now) {
        P p = players.get(lookup(name));
        if (p == null || p.samples.isEmpty()) return "暂无数据";
        if (p.left > 0) return "已下线（" + Text.time(p.left).substring(11, 16) + "）";
        long window = 5 * 60_000L;
        Map<String, Integer> c = counts(p, now, 5);
        List<Sample> recent = recentSamples(p, now, window);
        Sample last = p.samples.peekLast();
        List<String> labels = new ArrayList<>();
        if (last.sleeping()) labels.add("睡觉");
        if (last.vehicle() != null) labels.add("骑着 " + shortId(last.vehicle()));
        int breaks = sum(c, "break", null), places = sum(c, "place", null);
        int ores = sum(c, "break", k -> k.contains("_ore") || k.contains("ancient_debris"));
        int logs = sum(c, "break", k -> k.endsWith("_log") || k.endsWith("_stem") || k.endsWith("_wood"));
        int crops = sum(c, "break", PlayerTracker::isCrop) + sum(c, "place", PlayerTracker::isCrop);
        int kills = sum(c, "kill", null), hurts = sum(c, "hurt", null), crafts = sum(c, "craft", null), uses = sum(c, "use", null);
        double dist = distance(recent);
        double avgY = recent.stream().mapToDouble(Sample::y).average().orElse(last.y());
        if (ores >= 3 || (breaks >= 20 && avgY < 50 && last.dim().endsWith("overworld"))) labels.add("挖矿");
        else if (logs >= 5) labels.add("砍树");
        if (crops >= 3) labels.add("种田/收菜");
        if (places >= 15) labels.add("建造");
        else if (breaks >= 30 && !labels.contains("挖矿") && !labels.contains("砍树")) labels.add("挖方块/清地形");
        if (kills >= 3 || hurts >= 4) labels.add("战斗");
        int ents = sum(c, "interact_entity", null), moved = sum(c, "take", null) + sum(c, "put", null);
        if (moved >= 1 || uses >= 4) labels.add(moved >= 1 ? "整理箱子/存取物品" : "操作方块/机器");
        if (ents >= 3) labels.add("和生物/实体互动");
        if (crafts >= 3) labels.add("合成制作");
        if (dist > 300) labels.add("赶路/探索（5 分钟移动约 " + Math.round(dist) + " 格）");
        boolean idle = breaks + places + kills + hurts + crafts + uses + ents + moved == 0 && dist < 3 && recent.size() >= 2
                && recent.get(recent.size() - 1).time() - recent.get(0).time() >= window - 30_000L;
        if (idle && !last.sleeping()) labels.add("挂机（5 分钟没动也没操作）");
        if (labels.isEmpty()) labels.add(dist > 20 ? "走动" : "闲逛/其他");
        return String.join("、", new LinkedHashSet<>(labels));
    }

    /** 某玩家最近 minutes 分钟的完整行为数据（JSON），轨迹最多 12 个点、事件最多 40 条。 */
    public synchronized JsonObject activity(String name, int minutes, long now) {
        return activity(name, minutes, now, 12, 40, Set.of());
    }

    /**
     * 某玩家最近 minutes 分钟的完整行为数据（JSON）。
     * pathPoints：轨迹抽样点数（采样每 10 秒一个，抽样后再补最后一点）；eventLimit：最多返回几条事件（取最新的）；
     * eventKinds：只返回这些 kind 的事件（空 = 全部）。
     */
    public synchronized JsonObject activity(String name, int minutes, long now, int pathPoints, int eventLimit, Set<String> eventKinds) {
        JsonObject o = new JsonObject();
        o.addProperty("player", name);
        P p = players.get(lookup(name));
        if (p == null) {
            o.addProperty("error", "没有这名玩家的记录（追踪只保存最近 " + historyMs / 60_000L + " 分钟）");
            return o;
        }
        long window = minutes * 60_000L;
        o.addProperty("online", p.left == 0);
        o.addProperty("window_minutes", minutes);
        o.addProperty("session_minutes", p.joined > 0 ? (now - p.joined) / 60_000L : null);
        o.addProperty("doing", guess(name, now));
        List<Sample> recent = recentSamples(p, now, window);
        if (!recent.isEmpty()) {
            Sample last = recent.get(recent.size() - 1);
            o.add("now", sampleJson(last));
            o.addProperty("distance_moved", Math.round(distance(recent)));
            Set<String> dims = new LinkedHashSet<>();
            Set<String> biomes = new LinkedHashSet<>();
            for (Sample s : recent) {
                dims.add(s.dim());
                if (s.biome() != null) biomes.add(shortId(s.biome()));
            }
            o.add("dimensions", strings(dims));
            o.add("biomes", strings(biomes));
            JsonArray path = new JsonArray();
            int pts = Math.max(1, pathPoints);
            int step = Math.max(1, (recent.size() + pts - 1) / pts); // 最多 pts 个点 + 最后一个点
            for (int i = 0; i < recent.size(); i += step) path.add(pathPoint(recent.get(i)));
            if ((recent.size() - 1) % step != 0) path.add(pathPoint(last));
            o.add("path", path);
        }
        Map<String, Integer> c = counts(p, now, minutes);
        JsonObject counts = new JsonObject();
        for (String k : KINDS) {
            JsonObject top = top(c, k, 15);
            if (!top.isEmpty()) counts.add(k, top);
        }
        o.add("counts", counts);
        JsonArray areas = areas(p, now, minutes, 8);
        if (!areas.isEmpty()) o.add("areas", areas);
        JsonArray notes = new JsonArray();
        List<Notable> ns = new ArrayList<>();
        for (Notable n : p.notable) {
            if (now - n.time() <= window && (eventKinds.isEmpty() || eventKinds.contains(n.kind()))) ns.add(n);
        }
        int limit = Math.max(1, eventLimit);
        if (ns.size() > limit) o.addProperty("events_omitted", ns.size() - limit);
        for (Notable n : ns.subList(Math.max(0, ns.size() - limit), ns.size())) notes.add(notableJson(n));
        o.add("events", notes);
        JsonObject inv = inventoryChange(p, now, window);
        if (inv != null) o.add("inventory_change", inv);
        return o;
    }

    /** 最近 n 条事件（不含进出服），给"大家在干嘛"的总览用。 */
    public synchronized JsonArray recentEvents(String name, int n, long now, long windowMs) {
        JsonArray a = new JsonArray();
        P p = players.get(lookup(name));
        if (p == null) return a;
        List<Notable> ns = new ArrayList<>();
        for (Notable x : p.notable) {
            if (now - x.time() <= windowMs && !x.kind().equals("join") && !x.kind().equals("leave")) ns.add(x);
        }
        for (Notable x : ns.subList(Math.max(0, ns.size() - n), ns.size())) a.add(notableJson(x));
        return a;
    }

    // ———————————————————— 计算 ————————————————————

    private static JsonObject notableJson(Notable n) {
        JsonObject x = new JsonObject();
        x.addProperty("time", Text.time(n.time()).substring(11));
        if (n.times() > 1) {
            x.addProperty("times", n.times());
            x.addProperty("since", Text.time(n.first()).substring(11));
        }
        x.addProperty("kind", n.kind());
        x.addProperty("detail", n.detail());
        if (n.where() != null) x.addProperty("where", n.where());
        return x;
    }

    /** 挖/放方块最集中的几个区块：给出区块中心坐标和平均高度。 */
    private static JsonArray areas(P p, long now, int minutes, int limit) {
        Map<String, long[]> sum = new HashMap<>();
        long from = now / 60_000L - minutes + 1;
        for (Bucket b : p.buckets) {
            if (b.minute < from) continue;
            b.areas.forEach((k, v) -> {
                long[] t = sum.computeIfAbsent(k, kk -> new long[2]);
                t[0] += v[0];
                t[1] += v[1];
            });
        }
        JsonArray out = new JsonArray();
        sum.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(limit).forEach(e -> {
            String[] k = e.getKey().split("\\|");
            long cnt = e.getValue()[0];
            JsonObject o = new JsonObject();
            o.addProperty("kind", k[0]);
            o.addProperty("near", shortId(k[1]) + " " + (Integer.parseInt(k[2]) * 16 + 8) + " " + Math.round((double) e.getValue()[1] / cnt)
                    + " " + (Integer.parseInt(k[3]) * 16 + 8));
            o.addProperty("count", cnt);
            out.add(o);
        });
        return out;
    }

    private static List<Sample> recentSamples(P p, long now, long window) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : p.samples) if (now - s.time() <= window) out.add(s);
        return out;
    }

    /** 水平移动距离；换维度、传送（单次 > 200 格）不计入。 */
    static double distance(List<Sample> s) {
        double d = 0;
        for (int i = 1; i < s.size(); i++) {
            Sample a = s.get(i - 1), b = s.get(i);
            if (!a.dim().equals(b.dim())) continue;
            double step = Math.hypot(b.x() - a.x(), b.z() - a.z());
            if (step <= 200) d += step;
        }
        return d;
    }

    private static Map<String, Integer> counts(P p, long now, int minutes) {
        Map<String, Integer> out = new HashMap<>();
        long from = now / 60_000L - minutes + 1;
        for (Bucket b : p.buckets) if (b.minute >= from) b.counts.forEach((k, v) -> out.merge(k, v, Integer::sum));
        return out;
    }

    private static int sum(Map<String, Integer> c, String kind, java.util.function.Predicate<String> detail) {
        int n = 0;
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            int bar = e.getKey().indexOf('|');
            if (!e.getKey().substring(0, bar).equals(kind)) continue;
            if (detail == null || detail.test(e.getKey().substring(bar + 1))) n += e.getValue();
        }
        return n;
    }

    private static JsonObject top(Map<String, Integer> c, String kind, int limit) {
        JsonObject o = new JsonObject();
        c.entrySet().stream().filter(e -> e.getKey().startsWith(kind + "|"))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit)
                .forEach(e -> o.addProperty(shortId(e.getKey().substring(kind.length() + 1)), e.getValue()));
        return o;
    }

    private static JsonObject inventoryChange(P p, long now, long window) {
        if (p.inventories.size() < 2) return null;
        Map<String, Integer> cur = p.inventories.peekLast().getValue();
        Map<String, Integer> old = null;
        for (Map.Entry<Long, Map<String, Integer>> e : p.inventories) {
            if (now - e.getKey() <= window) {
                old = e.getValue();
                break;
            }
        }
        if (old == null || old == cur) return null;
        Map<String, Integer> diff = new HashMap<>();
        for (Map.Entry<String, Integer> e : cur.entrySet()) diff.merge(e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : old.entrySet()) diff.merge(e.getKey(), -e.getValue(), Integer::sum);
        JsonObject gained = new JsonObject(), lost = new JsonObject();
        diff.entrySet().stream().filter(e -> e.getValue() > 0).sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(20)
                .forEach(e -> gained.addProperty(shortId(e.getKey()), e.getValue()));
        diff.entrySet().stream().filter(e -> e.getValue() < 0).sorted(Map.Entry.comparingByValue()).limit(20)
                .forEach(e -> lost.addProperty(shortId(e.getKey()), -e.getValue()));
        JsonObject o = new JsonObject();
        o.add("gained", gained);
        o.add("lost", lost);
        return o;
    }

    private static boolean isCrop(String id) {
        return CROPS.contains(shortId(id));
    }

    static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String lastWhere(P p) {
        Sample s = p.samples.peekLast();
        return s == null ? null : where(s);
    }

    public static String where(Sample s) {
        return shortId(s.dim()) + " " + (int) Math.floor(s.x()) + " " + (int) Math.floor(s.y()) + " " + (int) Math.floor(s.z());
    }

    private static JsonObject sampleJson(Sample s) {
        JsonObject o = new JsonObject();
        o.addProperty("where", where(s));
        if (s.biome() != null) o.addProperty("biome", shortId(s.biome()));
        o.addProperty("health", Math.round(s.health()) + "/" + Math.round(s.maxHealth()));
        o.addProperty("food", s.food());
        if (s.held() != null) o.addProperty("holding", shortId(s.held()));
        o.addProperty("gamemode", s.gameMode());
        if (s.vehicle() != null) o.addProperty("riding", shortId(s.vehicle()));
        if (s.sleeping()) o.addProperty("sleeping", true);
        return o;
    }

    private static JsonObject pathPoint(Sample s) {
        JsonObject o = new JsonObject();
        o.addProperty("time", Text.time(s.time()).substring(11, 16));
        o.addProperty("where", where(s));
        if (s.biome() != null) o.addProperty("biome", shortId(s.biome()));
        return o;
    }

    private static JsonArray strings(Set<String> s) {
        JsonArray a = new JsonArray();
        s.forEach(a::add);
        return a;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
