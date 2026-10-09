package io.github.aftsakee.aiadmin.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import io.github.aftsakee.aiadmin.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 每个玩家的目标进度（aiadmin/goals-progress.json，在 MC 存档目录旁的 aiadmin/ 状态目录里）：
 * 已完成的阶段、个人目标、定时提示的开关和上次提示。
 * <p>并发：主线程（自动检查、/goal 指令）和代理线程（AI 工具 set_player_goal / goal_status）都会读写，所有方法 synchronized；
 * 读出去的都是副本。写盘走 {@link JsonFiles#writeQuietly}（唯一临时文件 + ATOMIC_MOVE），写不了只保留在内存（不影响游戏），
 * 失败由 JsonFiles 上报、说明见 {@link #saveError()}。
 * <p>读坏不覆盖（AGENTS.md 约束），见 {@link #load()}。
 * <p>玩家按小写名字作键（离线服务器的 UUID 会随名字变化，名字更稳定，和 PlayerTracker 一致）。
 */
public final class GoalStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    /** 已完成的个人目标保留多久（之后清掉，免得列表越来越长）。 */
    public static final long DONE_KEEP_MS = 24 * 3600_000L;

    public static final class Personal {
        public String item;
        public int count;
        public String note;
        public long created;
        public long done;
        public String setBy;

        public Personal copy() {
            Personal p = new Personal();
            p.item = item;
            p.count = count;
            p.note = note;
            p.created = created;
            p.done = done;
            p.setBy = setBy;
            return p;
        }
    }

    public static final class Player {
        public String name;
        public Map<String, Long> stagesDone = new LinkedHashMap<>();
        public List<Personal> personal = new ArrayList<>();
        public boolean hintsOff;
        public long lastHint;
        public String lastHintKey;

        Player copy() {
            Player p = new Player();
            p.name = name;
            p.stagesDone = new LinkedHashMap<>(stagesDone);
            for (Personal x : personal) p.personal.add(x.copy());
            p.hintsOff = hintsOff;
            p.lastHint = lastHint;
            p.lastHintKey = lastHintKey;
            return p;
        }
    }

    private static final class State {
        Map<String, Player> players = new LinkedHashMap<>();
    }

    private final Path file;
    private State st = new State();
    private String loadError;
    private String saveError;

    public GoalStore(Path file) {
        this.file = file;
        load();
    }

    /**
     * 读进度文件。原因、边界、约束：
     * <ul>
     *   <li>整份读不了（不是合法 JSON）：JsonFiles.load 已把原文件改名为 .corrupt-时间戳 并上报，这里从空开始——下次保存写的是新文件，
     *       不会覆盖原文件；</li>
     *   <li>结构不对（顶层不是对象、players 不是对象）：同样隔离原文件后从空开始；</li>
     *   <li>个别玩家记录坏了（值是 null、字段类型不对）或个别个人目标没有物品：只跳过那一条，其余照常读入（旧实现一个 null 就 NPE 清空整份）；
     *       跳过时先把原文件复制一份 .corrupt-时间戳 留证，因为下一次保存会把跳过的部分写没。</li>
     * </ul>
     * 物品为空的个人目标必须丢掉：GoalEngine.count 对每个个人目标做 item.startsWith，空值会让每次检查都抛异常。
     */
    private void load() {
        JsonFiles.Load l = JsonFiles.load(file);
        if (l.json() == null) {
            loadError = l.error();
            return;
        }
        if (!l.json().isJsonObject()) {
            loadError = JsonFiles.quarantine(file, "顶层不是对象");
            return;
        }
        JsonElement pe = l.json().getAsJsonObject().get("players");
        if (pe == null || pe.isJsonNull()) return; // {}：还没有任何玩家
        if (!pe.isJsonObject()) {
            loadError = JsonFiles.quarantine(file, "players 不是对象");
            return;
        }
        int badPlayers = 0, badItems = 0;
        for (Map.Entry<String, JsonElement> e : pe.getAsJsonObject().entrySet()) {
            Player p = null;
            try {
                if (e.getValue().isJsonObject()) p = GSON.fromJson(e.getValue(), Player.class);
            } catch (RuntimeException ex) {
                p = null; // 这一个玩家的字段类型不对：只跳过他
            }
            if (p == null) {
                badPlayers++;
                continue;
            }
            badItems += sanitize(p, e.getKey());
            st.players.put(key(e.getKey()), p);
        }
        if (badPlayers + badItems > 0) {
            loadError = backup("有 " + badPlayers + " 个玩家记录、" + badItems + " 条阶段/个人目标记录读不了，已跳过（其余照常）");
        }
    }

    /** 补齐缺省字段、去掉坏的子记录；返回去掉了几条。 */
    private static int sanitize(Player p, String key) {
        int bad = 0;
        if (p.name == null || p.name.isBlank()) p.name = key;
        Map<String, Long> stages = new LinkedHashMap<>();
        if (p.stagesDone != null) {
            for (Map.Entry<String, Long> s : p.stagesDone.entrySet()) {
                if (s.getKey() != null && s.getValue() != null) stages.put(s.getKey(), s.getValue());
                else bad++;
            }
        }
        p.stagesDone = stages;
        List<Personal> personal = new ArrayList<>();
        if (p.personal != null) {
            for (Personal x : p.personal) {
                if (x != null && x.item != null && !x.item.isBlank()) personal.add(x);
                else bad++;
            }
        }
        p.personal = personal;
        return bad;
    }

    /** 部分记录跳过时复制一份原文件留证（不改名：原文件里其余记录照常使用，之后的保存会正常替换它）。 */
    private String backup(String why) {
        Path bak = file.resolveSibling(file.getFileName() + ".corrupt-" + ZonedDateTime.now(ZoneOffset.UTC).format(STAMP));
        try {
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            return file.getFileName() + " " + why + "；原文件已备份为 " + bak.getFileName();
        } catch (IOException e) {
            return file.getFileName() + " " + why + "；备份原文件失败（" + e.getMessage() + "）";
        }
    }

    /** 启动时读文件遇到的问题（整份隔离或跳过了部分记录）；没有问题返回 null。 */
    public synchronized String loadError() {
        return loadError;
    }

    /** 最近一次保存失败的说明；最近一次保存成功（或还没保存过）返回 null。 */
    public synchronized String saveError() {
        return saveError;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** 读一个玩家的副本（没有记录时返回空记录）。 */
    public synchronized Player get(String name) {
        Player p = st.players.get(key(name));
        if (p == null) {
            p = new Player();
            p.name = name;
            return p;
        }
        return p.copy();
    }

    /**
     * 改一个玩家的记录（定期检查的结果）。不立即写盘：主线程每个检查周期对每名在线玩家都会调用，
     * 由调用方在一轮检查结束后 {@link #saveIfDirty}（关服时也会调用）。指令、AI 工具的改动（个人目标等）仍立即保存。
     */
    public synchronized void update(String name, Consumer<Player> f) {
        Player p = st.players.computeIfAbsent(key(name), k -> {
            Player n = new Player();
            n.name = name;
            return n;
        });
        p.name = name;
        f.accept(p);
        dirty = true;
    }

    private boolean dirty;

    /** 有未保存的检查结果就写盘。 */
    public synchronized void saveIfDirty() {
        if (dirty) save();
    }

    /** 添加个人目标。返回 null = 成功，否则是失败原因。同一物品已有未完成目标时改成新数量。 */
    public synchronized String addPersonal(String name, String item, int count, String note, String setBy, int max, long now) {
        if (item == null || item.isBlank()) return "没有指定物品";
        Player p = st.players.computeIfAbsent(key(name), k -> {
            Player n = new Player();
            n.name = name;
            return n;
        });
        for (Personal x : p.personal) {
            if (x.done == 0 && x.item.equals(item)) {
                x.count = count;
                if (note != null && !note.isBlank()) x.note = note;
                save();
                return null;
            }
        }
        long open = p.personal.stream().filter(x -> x.done == 0).count();
        if (open >= max) return "个人目标最多 " + max + " 个，先完成或删掉一些";
        Personal x = new Personal();
        x.item = item;
        x.count = count;
        x.note = note == null ? "" : note.trim();
        x.created = now;
        x.setBy = setBy;
        p.personal.add(x);
        save();
        return null;
    }

    /** 删除第 index 个（从 1 开始，按 /goal 列出的顺序 = 未完成在前）或某物品的个人目标。返回删掉的那个，没有返回 null。 */
    public synchronized Personal removePersonal(String name, String itemOrIndex) {
        Player p = st.players.get(key(name));
        if (p == null) return null;
        List<Personal> ordered = ordered(p.personal);
        Personal hit = null;
        try {
            int i = Integer.parseInt(itemOrIndex.trim());
            if (i >= 1 && i <= ordered.size()) hit = ordered.get(i - 1);
        } catch (NumberFormatException e) {
            for (Personal x : ordered) if (x.item.equals(itemOrIndex)) {
                hit = x;
                break;
            }
        }
        if (hit == null) return null;
        p.personal.remove(hit);
        save();
        return hit.copy();
    }

    public synchronized int clearPersonal(String name) {
        Player p = st.players.get(key(name));
        if (p == null) return 0;
        int n = p.personal.size();
        p.personal.clear();
        save();
        return n;
    }

    /** 未完成的在前（按添加顺序），已完成的在后。 */
    public static List<Personal> ordered(List<Personal> l) {
        List<Personal> r = new ArrayList<>();
        for (Personal x : l) if (x.done == 0) r.add(x);
        for (Personal x : l) if (x.done != 0) r.add(x);
        return r;
    }

    /** 清掉完成超过一天的个人目标。 */
    public synchronized void prune(long now) {
        boolean changed = false;
        for (Player p : st.players.values()) changed |= p.personal.removeIf(x -> x.done != 0 && now - x.done > DONE_KEEP_MS);
        if (changed) save();
    }

    private void save() {
        dirty = false;
        if (file == null) return;
        saveError = JsonFiles.writeQuietly(file, GSON.toJson(st)); // 失败已由 JsonFiles 上报；内存里的进度照常使用
    }
}
