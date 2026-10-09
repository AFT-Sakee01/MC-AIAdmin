package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.util.JsonFiles;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 指令资料：
 * <ul>
 *   <li>原版指令：随模组打包的只读资料（resources/aiadmin/vanilla-commands.json，1.21.1）——用途、写法、单位、怎么恢复、风险。</li>
 *   <li>学到的笔记（aiadmin/command-notes.json）：模组指令没有现成资料，AI 被要求用到时先查格式、小范围试，用过后用 note_command 记下
 *       单位、效果、怎么恢复，下次（本地或云端）直接查到。</li>
 * </ul>
 * "已验证"不能由模型自己说了算：只有最近 30 分钟内机器人真的成功执行过这条指令（任何途径：本地、云端、远程，都经 ActionTools 记到这里），
 * 笔记才标成已验证；否则记成"未验证"，并注明是谁说的（玩家在聊天里说的不算事实）。
 * <p>每条指令最多 10 条笔记（满了先删最旧的未验证笔记），最多 500 条指令。线程：同一把锁。
 */
public final class CommandNotes {
    static final int PER_COMMAND = 10;
    static final int MAX_COMMANDS = 500;
    static final long VERIFY_WINDOW_MS = 30 * 60_000L;

    public static final class Note {
        public long time;
        public String by;
        public String text;
        public boolean verified;
        /** 验证时最近一次成功执行的完整指令（已验证时才有）。 */
        public String example;
    }

    private record Run(long time, String root, String command, boolean ok) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile JsonObject vanilla;
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, List<Note>> notes = new LinkedHashMap<>();
    private final ArrayDeque<Run> runs = new ArrayDeque<>();
    private String error;

    public CommandNotes(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * "/minecraft:give @p x" → "give"；"pig" → "pig"；"othermod:pig" → "othermod:pig"。
     * 只去掉 minecraft: 命名空间：别的模组的同名指令是另一条指令，执行成功不能拿来"验证"这条的笔记。
     */
    public static String root(String command) {
        String s = command == null ? "" : command.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        String r = (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
        return r.startsWith("minecraft:") ? r.substring("minecraft:".length()) : r;
    }

    // ———————————————————— 原版资料 ————————————————————

    /** 随模组打包的原版指令资料；读不到时返回空对象（不影响别的功能）。 */
    public static JsonObject vanilla() {
        JsonObject v = vanilla;
        if (v != null) return v;
        try (InputStream in = CommandNotes.class.getResourceAsStream("/aiadmin/vanilla-commands.json")) {
            v = in == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            v = new JsonObject();
        }
        vanilla = v;
        return v;
    }

    /** 某条原版指令的资料，没有返回 null。 */
    public static JsonObject vanilla(String root) {
        JsonObject c = vanilla().getAsJsonObject("commands");
        return c != null && c.has(root) ? c.getAsJsonObject(root) : null;
    }

    // ———————————————————— 执行记录（ActionTools 调用） ————————————————————

    /** 机器人执行了一条指令（成功或失败）。只在内存里留最近 200 条。 */
    public synchronized void recordRun(String command, boolean ok) {
        long now = clock.getAsLong();
        // execute … run pig：里面的 pig 也算执行过（每层各记一条，和黑名单的分层一致）
        for (String layer : io.github.aftsakee.aiadmin.policy.CommandPolicy.layers(io.github.aftsakee.aiadmin.policy.CommandPolicy.normalize(command))) {
            runs.addLast(new Run(now, root(layer), Text.brief(command, 200), ok));
        }
        while (runs.size() > 200) runs.pollFirst();
    }

    /** 最近 30 分钟内这条指令成功执行过的最后一次（完整指令），没有返回 null。 */
    public synchronized String lastSuccess(String root) {
        long now = clock.getAsLong();
        for (var it = runs.descendingIterator(); it.hasNext(); ) {
            Run r = it.next();
            if (now - r.time() > VERIFY_WINDOW_MS) break;
            if (r.ok() && r.root().equals(root)) return r.command();
        }
        return null;
    }

    // ———————————————————— 笔记 ————————————————————

    /** 记一条笔记。wantVerified 只有在最近真的成功执行过时才生效。返回说明文字。 */
    public synchronized String add(String command, String text, boolean wantVerified, String by) {
        String r = root(command);
        if (r.isEmpty() || !r.matches("[a-z0-9_.\\-]{1,64}(:[a-z0-9_.\\-/]{1,64})?")) return "指令名不对：" + command;
        String t = text == null ? "" : text.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        if (t.isEmpty()) return "笔记不能为空";
        if (t.length() > 300) t = t.substring(0, 300);
        List<Note> list = notes.get(r);
        if (list == null) {
            if (notes.size() >= MAX_COMMANDS) notes.remove(notes.keySet().iterator().next());
            list = new ArrayList<>();
            notes.put(r, list);
        }
        Note n = new Note();
        n.time = clock.getAsLong();
        n.by = by == null ? "" : by;
        n.text = t;
        String ex = wantVerified ? lastSuccess(r) : null;
        n.verified = ex != null;
        n.example = ex;
        if (list.size() >= PER_COMMAND) {
            int drop = 0;
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).verified) {
                    drop = i;
                    break;
                }
            }
            list.remove(drop);
        }
        list.add(n);
        save();
        return "已记下 /" + r + " 的笔记" + (n.verified ? "（已验证：最近成功执行过 " + ex + "；只证明这条指令能用，笔记里写的单位/效果仍是 AI 的观察）"
                : wantVerified ? "（标成未验证：最近 30 分钟内机器人没有成功执行过 /" + r + "）" : "（未验证）");
    }

    /** 服主或 AI 删掉写错的笔记（序号从 1 开始）。 */
    public synchronized String remove(String command, int index) {
        String r = root(command);
        List<Note> list = notes.get(r);
        if (list == null || index < 1 || index > list.size()) return "没有这条笔记";
        list.remove(index - 1);
        if (list.isEmpty()) notes.remove(r);
        save();
        return "已删除 /" + r + " 的第 " + index + " 条笔记";
    }

    public synchronized JsonArray json(String command) {
        JsonArray a = new JsonArray();
        List<Note> list = notes.get(root(command));
        if (list == null) return a;
        int i = 1;
        for (Note n : list) {
            JsonObject o = new JsonObject();
            o.addProperty("index", i++);
            o.addProperty("time", Text.time(n.time));
            o.addProperty("by", n.by);
            o.addProperty("verified", n.verified);
            if (n.verified) o.addProperty("verified_means", "最近真的成功执行过这条指令（不代表笔记内容逐字正确）");
            if (n.example != null) o.addProperty("verified_with", n.example);
            o.addProperty("text", n.text);
            a.add(o);
        }
        return a;
    }

    public synchronized boolean has(String root) {
        return notes.containsKey(root);
    }

    public synchronized List<String> roots() {
        return new ArrayList<>(notes.keySet());
    }

    public synchronized String error() {
        return error;
    }

    /** 读坏不覆盖（见 JsonFiles）；个别指令的笔记坏了跳过那一条。 */
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
        JsonElement ce = l.json().getAsJsonObject().get("commands");
        if (ce == null || !ce.isJsonObject()) return;
        int bad = 0;
        for (Map.Entry<String, JsonElement> x : ce.getAsJsonObject().entrySet()) {
            try {
                List<Note> list = new ArrayList<>();
                for (JsonElement n : x.getValue().getAsJsonArray()) {
                    Note note = GSON.fromJson(n, Note.class);
                    if (note != null && note.text != null) list.add(note);
                }
                if (!list.isEmpty()) notes.put(x.getKey(), list);
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (bad > 0) error = JsonFiles.keepBadCopy(file, bad);
    }

    private void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 1);
        JsonObject c = new JsonObject();
        for (Map.Entry<String, List<Note>> e : notes.entrySet()) c.add(e.getKey(), GSON.toJsonTree(e.getValue()));
        o.add("commands", c);
        String err = JsonFiles.writeQuietly(file, GSON.toJson(o));
        if (err != null) error = err;
    }
}
