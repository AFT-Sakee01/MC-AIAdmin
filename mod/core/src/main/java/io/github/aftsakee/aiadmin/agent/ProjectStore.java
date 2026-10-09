package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.JsonFiles;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 玩家的工程和打算："今天建酒馆""准备打凋灵""去找末地城"。比"凑够 N 个物品"的个人目标更像人说的话，
 * 让 AI 能围绕玩家正在做的事接话、跟进进展，而不是只在死亡时出现。
 * <ul>
 *   <li>每个工程有地点（建的时候站的位置）和范围；成员在范围内放的方块计入进度，跨过 100/500/1000/2000/5000/10000… 时发一次"工程进展"事件。</li>
 *   <li>玩家自己用 /goal project 管理，AI 也能在玩家说起时帮他记（玩家聊天唤醒时只能替说话的人记）。</li>
 *   <li>进行中的工程每人最多 10 个，全服最多 300 个（超出时清掉最早结束的）。</li>
 * </ul>
 * 持久化 aiadmin/projects.json：增删改立即写；放方块计数只标脏，由定时器每分钟写一次（{@link #saveIfDirty}）。
 */
public final class ProjectStore {
    public static final List<String> KINDS = List.of("build", "boss", "explore", "farm", "other");
    public static final List<String> STATUSES = List.of("active", "done", "abandoned");
    static final int[] MILESTONES = {100, 500, 1000, 2000, 5000, 10000, 20000, 50000};
    static final int MAX_ACTIVE_PER_PLAYER = 10;
    static final int MAX_TOTAL = 300;

    public static final class Note {
        public long time;
        public String who;
        public String text;
    }

    public static final class Project {
        public String id;
        public String title;
        public String kind = "build";
        public String owner;
        public List<String> members = new ArrayList<>();
        public String dim;
        public int x;
        public int y;
        public int z;
        public int radius = 48;
        public String status = "active";
        public long created;
        public long updated;
        public List<Note> notes = new ArrayList<>();
        public long placed;
        public int reached;

        boolean involves(String player) {
            String k = player.toLowerCase(Locale.ROOT);
            return owner.toLowerCase(Locale.ROOT).equals(k) || members.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).equals(k));
        }

        boolean covers(String d, int px, int pz) {
            return dim != null && dim.equals(d) && Math.abs(px - x) <= radius && Math.abs(pz - z) <= radius;
        }
    }

    /** 工程进度跨过一个里程碑。 */
    public record Progress(Project project, int milestone) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Project> projects = new LinkedHashMap<>();
    private int seq;
    private boolean dirty;
    private String error;

    public ProjectStore(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public synchronized String create(String owner, String title, String kind, String dim, int x, int y, int z, int radius, String by) {
        String t = clean(title, 40);
        if (t.isEmpty()) return "工程名不能为空（40 字以内）";
        String k = kind == null || !KINDS.contains(kind) ? "build" : kind;
        long mine = projects.values().stream().filter(p -> p.status.equals("active") && p.owner.equalsIgnoreCase(owner)).count();
        if (mine >= MAX_ACTIVE_PER_PLAYER) return owner + " 进行中的工程已经有 " + mine + " 个，先完成或放弃一些";
        if (projects.size() >= MAX_TOTAL) evict();
        if (projects.size() >= MAX_TOTAL) return "全服工程数已满（" + MAX_TOTAL + " 个），先完成或放弃一些";
        Project p = new Project();
        p.id = "p" + (++seq);
        p.title = t;
        p.kind = k;
        p.owner = owner;
        p.dim = dim;
        p.x = x;
        p.y = y;
        p.z = z;
        p.radius = Math.max(8, Math.min(256, radius <= 0 ? 48 : radius));
        p.created = p.updated = clock.getAsLong();
        projects.put(p.id, p);
        addNote(p, by, "开始：" + t);
        save();
        return "已记下工程 " + p.id + "「" + t + "」（" + kindLabel(k) + (dim != null ? "，" + dim + " " + x + " " + y + " " + z + " 周围 " + p.radius + " 格" : "") + "）";
    }

    /**
     * 满了（MAX_TOTAL）时腾一个位置：优先淘汰最久没动的已结束工程；全是进行中的话，淘汰 STALE_MS 以上没动静的进行中工程
     * （玩家早就不玩了的）。都不符合就不淘汰——create 会因为数量满而失败，不会无限增长。
     */
    private void evict() {
        Project oldest = null;
        for (Project p : projects.values()) {
            if (!p.status.equals("active") && (oldest == null || p.updated < oldest.updated)) oldest = p;
        }
        if (oldest == null) {
            long now = clock.getAsLong();
            for (Project p : projects.values()) {
                if (now - p.updated > STALE_MS && (oldest == null || p.updated < oldest.updated)) oldest = p;
            }
        }
        if (oldest != null) projects.remove(oldest.id);
    }

    /** 进行中但这么久没有任何动静（没放方块、没记进展）的工程，满了的时候可以被淘汰。 */
    static final long STALE_MS = 60L * 86_400_000L;

    private void addNote(Project p, String who, String text) {
        Note n = new Note();
        n.time = clock.getAsLong();
        n.who = who == null ? "" : who;
        n.text = clean(text, 120);
        p.notes.add(n);
        while (p.notes.size() > 20) p.notes.remove(0);
        p.updated = n.time;
    }

    /** 内部用：活对象，只能在本对象的锁里读写。 */
    private Project find(String id) {
        return id == null ? null : projects.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 对外一律返回快照（深拷贝）：调用方在代理线程、远程线程上读 members/notes 时，主线程可能正在锁内改它们，
     * 交出活对象会 ConcurrentModificationException，在锁外改字段（radius）对定时落盘也不保证可见。改动请走本类的同步方法。
     */
    private static Project copy(Project p) {
        return p == null ? null : GSON.fromJson(GSON.toJsonTree(p), Project.class);
    }

    public synchronized Project get(String id) {
        return copy(find(id));
    }

    /** 这名玩家是不是这个工程的负责人或成员。 */
    public synchronized boolean involves(String id, String player) {
        Project p = find(id);
        return p != null && p.involves(player);
    }

    /** 负责人改范围（8~256 格）。 */
    public synchronized String setRadius(String id, String owner, int radius) {
        Project p = find(id);
        if (p == null) return "没有这个工程：" + id;
        if (!p.owner.equalsIgnoreCase(owner)) return "只有负责人能改范围（负责人 " + p.owner + "）";
        p.radius = Math.max(8, Math.min(256, radius));
        addNote(p, owner, "范围改为 " + p.radius + " 格");
        save();
        return "「" + p.title + "」的范围改为周围 " + p.radius + " 格";
    }

    /** 记一笔进展。 */
    public synchronized String note(String id, String who, String text) {
        Project p = find(id);
        if (p == null) return "没有这个工程：" + id;
        if (clean(text, 120).isEmpty()) return "进展不能为空";
        addNote(p, who, text);
        save();
        return "已记下「" + p.title + "」的进展";
    }

    public synchronized String status(String id, String status, String who) {
        Project p = find(id);
        if (p == null) return "没有这个工程：" + id;
        if (!STATUSES.contains(status)) return "状态只能是 active / done / abandoned";
        p.status = status;
        addNote(p, who, switch (status) {
            case "done" -> "完成";
            case "abandoned" -> "放弃";
            default -> "重新开始";
        });
        save();
        return "「" + p.title + "」" + (status.equals("done") ? "完成了" : status.equals("abandoned") ? "已放弃" : "重新开始");
    }

    public synchronized String join(String id, String player) {
        Project p = find(id);
        if (p == null) return "没有这个工程：" + id;
        if (p.involves(player)) return player + " 已经在「" + p.title + "」里了";
        if (p.members.size() >= 20) return "成员已满";
        p.members.add(player);
        addNote(p, player, player + " 加入");
        save();
        return player + " 加入了「" + p.title + "」";
    }

    public synchronized String leave(String id, String player) {
        Project p = find(id);
        if (p == null) return "没有这个工程：" + id;
        if (!p.members.removeIf(m -> m.equalsIgnoreCase(player))) return player + " 不是「" + p.title + "」的成员（负责人不能退出，可以放弃工程）";
        addNote(p, player, player + " 退出");
        save();
        return player + " 退出了「" + p.title + "」";
    }

    /** 这名玩家参与的工程（负责人或成员）。 */
    public synchronized List<Project> forPlayer(String player, boolean activeOnly) {
        List<Project> r = new ArrayList<>();
        for (Project p : projects.values()) if (p.involves(player) && (!activeOnly || p.status.equals("active"))) r.add(copy(p));
        return r;
    }

    public synchronized List<Project> all(String status) {
        List<Project> r = new ArrayList<>();
        for (Project p : projects.values()) if (status == null || status.equals("all") || p.status.equals(status)) r.add(copy(p));
        return r;
    }

    /** 覆盖这个位置的工程（任何状态）。 */
    public synchronized List<Project> at(String dim, int x, int z) {
        List<Project> r = new ArrayList<>();
        for (Project p : projects.values()) if (p.covers(dim, x, z)) r.add(copy(p));
        return r;
    }

    /** 放了一个方块：计入他参与的、覆盖这个位置的进行中工程；跨过里程碑的返回（调用方发事件）。 */
    public synchronized List<Progress> placed(String player, String dim, int x, int z) {
        List<Progress> r = new ArrayList<>();
        for (Project p : projects.values()) {
            if (!p.status.equals("active") || !p.covers(dim, x, z) || !p.involves(player)) continue;
            p.placed++;
            dirty = true;
            while (p.reached < MILESTONES.length && p.placed >= MILESTONES[p.reached]) {
                r.add(new Progress(copy(p), MILESTONES[p.reached]));
                p.reached++;
            }
        }
        // 跨过里程碑也只标脏、不在这里写：调用方在服务端主线程（放方块事件），项目规则不在主线程做文件 IO。
        // 调度层的定时器 30 秒内落盘（关服时再写一次）；万一这期间崩服，最坏是重启后同一里程碑再提示一次。
        return r;
    }

    public static String kindLabel(String k) {
        return switch (k) {
            case "build" -> "建筑";
            case "boss" -> "打 Boss";
            case "explore" -> "探索";
            case "farm" -> "农场/机器";
            default -> "其他";
        };
    }

    /** 一行说明（给提示词）。 */
    public static String line(Project p) {
        Note last = p.notes.isEmpty() ? null : p.notes.get(p.notes.size() - 1);
        return p.id + "「" + p.title + "」（" + kindLabel(p.kind) + "，负责人 " + p.owner + (p.members.isEmpty() ? "" : "，成员 " + String.join("、", p.members))
                + (p.dim != null ? "，" + p.dim + " " + p.x + " " + p.y + " " + p.z : "") + (p.placed > 0 ? "，范围内已放 " + p.placed + " 块" : "")
                + (last != null ? "，最近：" + last.text : "") + "）";
    }

    public static JsonObject json(Project p) {
        JsonObject o = GSON.toJsonTree(p).getAsJsonObject();
        o.addProperty("kind_label", kindLabel(p.kind));
        o.addProperty("created_at", Text.time(p.created));
        o.addProperty("updated_at", Text.time(p.updated));
        JsonArray ns = new JsonArray();
        for (Note n : p.notes) ns.add(Text.time(n.time).substring(5, 16) + " " + n.who + "：" + n.text);
        o.add("notes", ns);
        return o;
    }

    public static JsonArray json(Collection<Project> list) {
        JsonArray a = new JsonArray();
        for (Project p : list) a.add(json(p));
        return a;
    }

    public synchronized String error() {
        return error;
    }

    public synchronized void saveIfDirty() {
        if (dirty) save();
    }

    /** 读坏不覆盖（见 JsonFiles）；seq 取文件里的值和已有编号的最大值——seq 缺失或偏小时新建会生成已存在的编号、覆盖别人的工程。 */
    private void load() {
        if (file == null) return;
        JsonFiles.Load l = JsonFiles.load(file);
        if (!l.ok()) {
            error = l.error();
            return;
        }
        if (l.json() == null) return;
        if (!l.json().isJsonObject()) {
            error = JsonFiles.quarantine(file, "顶层不是对象");
            return;
        }
        JsonObject o = l.json().getAsJsonObject();
        int bad = 0;
        try {
            seq = o.has("seq") ? o.get("seq").getAsInt() : 0;
        } catch (RuntimeException ex) {
            seq = 0;
        }
        for (JsonElement x : o.has("projects") && o.get("projects").isJsonArray() ? o.getAsJsonArray("projects") : new JsonArray()) {
            try {
                Project p = GSON.fromJson(x, Project.class);
                if (p == null || p.id == null || p.owner == null || p.title == null) continue;
                if (p.members == null) p.members = new ArrayList<>();
                if (p.notes == null) p.notes = new ArrayList<>();
                if (!STATUSES.contains(p.status)) p.status = "active";
                if (!KINDS.contains(p.kind)) p.kind = "other";
                projects.put(p.id, p);
                if (p.id.matches("p\\d{1,9}")) seq = Math.max(seq, Integer.parseInt(p.id.substring(1)));
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (bad > 0) error = JsonFiles.keepBadCopy(file, bad);
    }

    private void save() {
        dirty = false;
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 1);
        o.addProperty("seq", seq);
        JsonArray a = new JsonArray();
        for (Project p : projects.values()) a.add(GSON.toJsonTree(p));
        o.add("projects", a);
        String err = JsonFiles.writeQuietly(file, GSON.toJson(o));
        if (err != null) error = err;
    }
}
