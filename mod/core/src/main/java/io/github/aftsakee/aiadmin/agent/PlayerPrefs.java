package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import io.github.aftsakee.aiadmin.util.JsonFiles;

/**
 * 每名玩家的互动偏好与状态：说话风格（欢迎调侃 / 只想认真答疑）、不想被提的话题、"别说了"（安静到某个时间）、连续受挫。
 * <p>为什么要有代码层的约束：提示词里写"别调侃他"模型不一定照做，所以这几条在执行前强制检查（见 {@link #chatBlocked}）：
 * <ul>
 *   <li>安静中的玩家：机器人不能私聊他、公开发言里不能点他的名字——除非这一轮就是他自己直接找机器人（@ 或 /ai 私聊）；
 *       他的事件（进服、死亡、成就…）不交给 AI；他在 all/custom 模式下没 @ 机器人的聊天不唤醒。</li>
 *   <li>不想被提的话题：发给他、提到他、或回他的话里出现这些词就不发。</li>
 *   <li>认真答疑、安静中、连续受挫的玩家进恶作剧免疫名单（{@link #noTease}），恶作剧指令不能对他们执行。</li>
 * </ul>
 * 连续受挫：window 分钟内死亡 ≥ deaths 次，从最后一次死亡起 calm 分钟内算受挫（只在内存里，重启清零）。
 * <p>持久化：aiadmin/player-prefs.json（原子替换写入）；线程：所有方法同一把锁，调用方在服务端主线程、代理线程、远程线程都有。
 */
public final class PlayerPrefs {
    public static final String NORMAL = "normal";
    public static final String ROAST_OK = "roast_ok";
    public static final String SERIOUS = "serious";
    public static final List<String> STYLES = List.of(NORMAL, ROAST_OK, SERIOUS);
    static final int MAX_AVOID = 10;
    static final int MAX_PLAYERS = 2000;

    /**
     * "别说了"一类的话。只有在对机器人说（@ 了机器人，或机器人刚对他说过话）时才算数，玩家之间的"别说了"不触发。
     * 故意写得保守：宁可漏判，也不要把玩家的普通聊天当成退出。
     */
    static final Pattern STOP = Pattern.compile("(别说了|别再说了|闭嘴|别吵了?|安静点|安静一下|别烦我|别@我|别艾特我|不要说了|不想听|shut\\s*up|stop\\s+talking|be\\s+quiet)",
            Pattern.CASE_INSENSITIVE);

    public static final class Pref {
        public String name;
        public String style = NORMAL;
        public List<String> avoid = new ArrayList<>();
        public long quietUntil;
        public String note = "";
        public long updated;
        public String updatedBy = "";

        Pref copy() {
            Pref p = new Pref();
            p.name = name;
            p.style = style;
            p.avoid = new ArrayList<>(avoid);
            p.quietUntil = quietUntil;
            p.note = note;
            p.updated = updated;
            p.updatedBy = updatedBy;
            return p;
        }
    }

    /** 受挫判定参数（来自 config.json 的 social）。 */
    public record Frustration(int deaths, int windowMinutes, int calmMinutes) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Pref> prefs = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Long>> deaths = new HashMap<>();
    private volatile Frustration frustration = new Frustration(3, 20, 30);
    private String loadError;

    public PlayerPrefs(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    public void frustration(Frustration f) {
        frustration = f;
    }

    static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    // ———————————————————— 查询 ————————————————————

    public synchronized Pref get(String name) {
        Pref p = prefs.get(key(name));
        return p == null ? null : p.copy();
    }

    public synchronized boolean quiet(String name) {
        Pref p = prefs.get(key(name));
        return p != null && p.quietUntil > clock.getAsLong();
    }

    public synchronized String style(String name) {
        Pref p = prefs.get(key(name));
        return p == null ? NORMAL : p.style;
    }

    public synchronized List<String> avoid(String name) {
        Pref p = prefs.get(key(name));
        return p == null ? List.of() : List.copyOf(p.avoid);
    }

    /** 连续受挫：最近 window 分钟内死亡次数够了，且离最后一次死亡不到 calm 分钟。 */
    public synchronized boolean frustrated(String name) {
        ArrayDeque<Long> d = deaths.get(key(name));
        if (d == null || d.isEmpty()) return false;
        Frustration f = frustration;
        long now = clock.getAsLong();
        long last = d.peekLast();
        if (now - last > f.calmMinutes() * 60_000L) return false;
        long n = d.stream().filter(t -> last - t <= f.windowMinutes() * 60_000L).count();
        return n >= f.deaths();
    }

    public synchronized int recentDeaths(String name, int minutes) {
        ArrayDeque<Long> d = deaths.get(key(name));
        if (d == null) return 0;
        long now = clock.getAsLong();
        return (int) d.stream().filter(t -> now - t <= minutes * 60_000L).count();
    }

    /** 不能整、不该调侃的玩家（小写名）：只想认真答疑、安静中、连续受挫。 */
    public synchronized Set<String> noTease() {
        Set<String> s = new TreeSet<>();
        long now = clock.getAsLong();
        for (Map.Entry<String, Pref> e : prefs.entrySet()) {
            if (SERIOUS.equals(e.getValue().style) || e.getValue().quietUntil > now) s.add(e.getKey());
        }
        for (String k : deaths.keySet()) if (frustrated(k)) s.add(k);
        return s;
    }

    /** 当前安静中的玩家（小写名 → 显示名）。 */
    synchronized Map<String, String> quietPlayers() {
        Map<String, String> m = new LinkedHashMap<>();
        long now = clock.getAsLong();
        for (Map.Entry<String, Pref> e : prefs.entrySet()) if (e.getValue().quietUntil > now) m.put(e.getKey(), e.getValue().name);
        return m;
    }

    // ———————————————————— 修改 ————————————————————

    private Pref mutable(String name) {
        String k = key(name);
        Pref p = prefs.get(k);
        if (p == null) {
            if (prefs.size() >= MAX_PLAYERS) pruneOldest();
            p = new Pref();
            p.name = name.trim();
            prefs.put(k, p);
        }
        return p;
    }

    private void pruneOldest() {
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Map.Entry<String, Pref> e : prefs.entrySet()) {
            if (e.getValue().updated < t) {
                t = e.getValue().updated;
                oldest = e.getKey();
            }
        }
        if (oldest != null) prefs.remove(oldest);
    }

    private void touch(Pref p, String by) {
        p.updated = clock.getAsLong();
        p.updatedBy = by == null ? "" : by;
        save();
    }

    /** 安静 hours 小时（0 = 解除）。玩家本人的明确设置（/ai quiet、/ai resume）用它：可以缩短。 */
    public synchronized String setQuiet(String name, double hours, String by) {
        Pref p = mutable(name);
        p.quietUntil = hours <= 0 ? 0 : clock.getAsLong() + (long) (hours * 3600_000L);
        touch(p, by);
        return hours <= 0 ? "已恢复：机器人会正常和 " + p.name + " 说话" : p.name + " 安静到 " + untilText(p.quietUntil);
    }

    /**
     * 至少安静 hours 小时：已有更长的安静就保持不变。聊天里说"别说了"（自动 2 小时）、事件/巡检里 AI 替人设安静用它——
     * 否则 /ai quiet 24 之后随口一句"别说了"会把安静缩短成 2 小时，AI 也能借"设安静"变相提前解除。
     */
    public synchronized String extendQuiet(String name, double hours, String by) {
        Pref p = mutable(name);
        long until = clock.getAsLong() + (long) (Math.max(0, hours) * 3600_000L);
        if (until > p.quietUntil) {
            p.quietUntil = until;
            touch(p, by);
        }
        return p.name + " 安静到 " + untilText(p.quietUntil);
    }

    /** "安静到"的显示：今天只写时分，跨天带上月-日（720 小时的安静只显示"14:00"会让人以为今天就结束）。 */
    private String untilText(long t) {
        String s = Text.time(t);
        String today = Text.time(clock.getAsLong());
        return s.substring(0, 10).equals(today.substring(0, 10)) ? s.substring(11, 16) : s.substring(5, 16);
    }

    public synchronized String setStyle(String name, String style, String by) {
        if (!STYLES.contains(style)) return "风格只能是 normal（正常）/ roast_ok（欢迎调侃）/ serious（只想认真答疑）";
        Pref p = mutable(name);
        p.style = style;
        touch(p, by);
        return p.name + " 的互动风格：" + styleLabel(style);
    }

    public synchronized String addAvoid(String name, String topic, String by) {
        String t = cleanTopic(topic);
        if (t.isEmpty()) return "话题不能为空（最多 20 个字）";
        Pref p = mutable(name);
        if (p.avoid.stream().anyMatch(x -> x.equalsIgnoreCase(t))) return p.name + " 已经设过不想提「" + t + "」";
        if (p.avoid.size() >= MAX_AVOID) return "最多 " + MAX_AVOID + " 个话题，先删掉一些";
        p.avoid.add(t);
        touch(p, by);
        return p.name + " 不想被提的话题：" + String.join("、", p.avoid);
    }

    public synchronized String removeAvoid(String name, String topic, String by) {
        Pref p = prefs.get(key(name));
        String t = cleanTopic(topic);
        if (p == null || !p.avoid.removeIf(x -> x.equalsIgnoreCase(t))) return "没有这个话题：" + t;
        touch(p, by);
        return p.avoid.isEmpty() ? p.name + " 没有不想被提的话题了" : p.name + " 不想被提的话题：" + String.join("、", p.avoid);
    }

    public synchronized String setNote(String name, String note, String by) {
        Pref p = mutable(name);
        p.note = Text.brief(note == null ? "" : note.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim(), 100);
        touch(p, by);
        return p.note.isEmpty() ? "已清除 " + p.name + " 的备注" : p.name + " 的备注：" + p.note;
    }

    static String cleanTopic(String t) {
        String s = t == null ? "" : t.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    /** 死亡记一次（服务端主线程，来自死亡事件）；只保留最近 2 小时、每人 50 次。 */
    public synchronized void recordDeath(String name, long now) {
        ArrayDeque<Long> d = deaths.computeIfAbsent(key(name), k -> new ArrayDeque<>());
        d.addLast(now);
        while (d.size() > 50 || (!d.isEmpty() && now - d.peekFirst() > 7200_000L)) d.pollFirst();
        if (deaths.size() > 1000) deaths.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > 7200_000L);
    }

    public static boolean stopPhrase(String text) {
        return text != null && STOP.matcher(text).find();
    }

    // ———————————————————— 给提示词和工具 ————————————————————

    public static String styleLabel(String style) {
        return switch (style) {
            case ROAST_OK -> "欢迎调侃";
            case SERIOUS -> "只想认真答疑，不要调侃";
            default -> "正常";
        };
    }

    /** 一个玩家的偏好一行说明；没有任何设置也没受挫时返回 null。 */
    public synchronized String line(String name) {
        Pref p = prefs.get(key(name));
        boolean fr = frustrated(name);
        long now = clock.getAsLong();
        if (p == null && !fr) return null;
        List<String> parts = new ArrayList<>();
        if (p != null && !NORMAL.equals(p.style)) parts.add(styleLabel(p.style));
        if (p != null && p.quietUntil > now) parts.add("说过\"别说了\"，安静到 " + untilText(p.quietUntil) + "：不要主动找他、不要在公屏提他，只回答他直接问你的");
        if (p != null && !p.avoid.isEmpty()) parts.add("不想被提的话题：" + String.join("、", p.avoid));
        if (p != null && !p.note.isEmpty()) parts.add("备注：" + p.note);
        if (fr) parts.add("连续受挫（最近 " + frustration.windowMinutes() + " 分钟死了 " + recentDeaths(name, frustration.windowMinutes()) + " 次）：收敛，别调侃，认真帮忙");
        if (parts.isEmpty()) return null;
        return (p != null ? p.name : name) + "：" + String.join("；", parts);
    }

    /** 文本里提到的、有设置或在受挫中的玩家（最多 limit 个），按出现顺序。 */
    public synchronized List<String> mentionedIn(String text, Collection<String> alsoNames, int limit) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : alsoNames) if (line(n) != null) out.add(n);
        String low = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (Pref p : prefs.values()) {
            if (out.size() >= limit) break;
            if (p.name.length() >= 3 && low.contains(key(p.name)) && line(p.name) != null) out.add(p.name);
        }
        for (String k : deaths.keySet()) {
            if (out.size() >= limit) break;
            if (k.length() >= 3 && low.contains(k) && frustrated(k)) out.add(k);
        }
        List<String> r = new ArrayList<>(out);
        return r.size() > limit ? r.subList(0, limit) : r;
    }

    public synchronized JsonObject json(String name) {
        JsonObject o = new JsonObject();
        Pref p = prefs.get(key(name));
        long now = clock.getAsLong();
        o.addProperty("player", p != null ? p.name : name);
        o.addProperty("style", p != null ? p.style : NORMAL);
        o.addProperty("style_label", styleLabel(p != null ? p.style : NORMAL));
        o.addProperty("quiet", p != null && p.quietUntil > now);
        if (p != null && p.quietUntil > now) o.addProperty("quiet_until", Text.time(p.quietUntil));
        JsonArray a = new JsonArray();
        if (p != null) p.avoid.forEach(a::add);
        o.add("avoid_topics", a);
        o.addProperty("note", p != null ? p.note : "");
        o.addProperty("frustrated", frustrated(name));
        o.addProperty("deaths_last_20_min", recentDeaths(name, 20));
        if (p != null && p.updated > 0) {
            o.addProperty("updated", Text.time(p.updated));
            o.addProperty("updated_by", p.updatedBy);
        }
        return o;
    }

    /**
     * 发言前检查（代码层强制）：返回 null = 可以发，否则是拒绝原因。
     * @param direct 这一轮直接找机器人的玩家（@ 了机器人或私聊机器人，小写名）：回他们本人不受"安静"限制
     */
    public synchronized String chatBlocked(String message, String target, Set<String> direct) {
        String low = message.toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        for (Map.Entry<String, Pref> e : prefs.entrySet()) {
            Pref p = e.getValue();
            String k = e.getKey();
            boolean isTarget = target != null && key(target).equals(k);
            boolean named = k.length() >= 3 && mentions(low, k);
            boolean speaking = direct.contains(k);
            if (p.quietUntil > now && !speaking && (isTarget || named)) {
                return p.name + " 说过\"别说了\"（安静到 " + untilText(p.quietUntil) + "）：不要私聊他，也不要在公屏提到他";
            }
            if (!p.avoid.isEmpty() && (isTarget || named || speaking)) {
                for (String t : p.avoid) {
                    if (!t.isEmpty() && low.contains(t.toLowerCase(Locale.ROOT))) return p.name + " 不想被提到「" + t + "」，换个说法，别提这个话题";
                }
            }
        }
        return null;
    }

    /**
     * 文本里是否把 name 当作一个完整的名字提到（前后不是字母、数字、下划线）：玩家名只由这些字符组成，
     * 子串匹配会让安静中的 Tom 挡掉所有含 "tomorrow" 的发言。中文紧挨着名字（"tom你好"）仍算提到。
     */
    static boolean mentions(String lowText, String lowName) {
        int from = 0;
        while (true) {
            int i = lowText.indexOf(lowName, from);
            if (i < 0) return false;
            int end = i + lowName.length();
            boolean before = i == 0 || !nameChar(lowText.charAt(i - 1));
            boolean after = end >= lowText.length() || !nameChar(lowText.charAt(end));
            if (before && after) return true;
            from = i + 1;
        }
    }

    private static boolean nameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * 恶作剧免疫名单（小写名）：设置文件的 persona.prank.immune_players，加上只想认真答疑、安静中、连续受挫的玩家。
     * 提示词里列出的和执行前强制检查的（ActionTools）必须是同一份，所以只在这里算。prefs 可为 null（机器人还没启动）。
     */
    public static java.util.Set<String> prankImmune(io.github.aftsakee.aiadmin.config.BotConfig c, PlayerPrefs prefs) {
        java.util.Set<String> s = new java.util.TreeSet<>();
        for (String n : c.persona.prank.immunePlayers) s.add(n.toLowerCase(Locale.ROOT));
        if (prefs != null) s.addAll(prefs.noTease());
        return s;
    }

    // ———————————————————— 持久化 ————————————————————

    public synchronized String loadError() {
        return loadError;
    }

    /** 读坏不覆盖：整份读不了时原文件改名隔离（JsonFiles.load）；个别记录坏了跳过那一条并复制留证，其余照常。 */
    private void load() {
        if (file == null) return;
        JsonFiles.Load l = JsonFiles.load(file);
        if (!l.ok()) {
            loadError = l.error();
            return;
        }
        if (l.json() == null) return;
        if (!l.json().isJsonObject()) {
            loadError = JsonFiles.quarantine(file, "顶层不是对象");
            return;
        }
        JsonObject o = l.json().getAsJsonObject();
        int bad = 0;
        for (JsonElement e : o.has("players") && o.get("players").isJsonArray() ? o.getAsJsonArray("players") : new JsonArray()) {
            try {
                Pref p = GSON.fromJson(e, Pref.class);
                if (p == null || p.name == null || p.name.isBlank()) continue;
                if (!STYLES.contains(p.style)) p.style = NORMAL;
                if (p.avoid == null) p.avoid = new ArrayList<>();
                if (p.note == null) p.note = "";
                if (p.updatedBy == null) p.updatedBy = "";
                prefs.put(key(p.name), p);
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (bad > 0) loadError = JsonFiles.keepBadCopy(file, bad);
    }

    private void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 1);
        JsonArray a = new JsonArray();
        for (Pref p : prefs.values()) a.add(GSON.toJsonTree(p));
        o.add("players", a);
        String err = JsonFiles.writeQuietly(file, GSON.toJson(o));
        if (err != null) loadError = err;
    }
}
