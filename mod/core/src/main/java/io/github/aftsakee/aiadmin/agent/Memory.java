package io.github.aftsakee.aiadmin.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import io.github.aftsakee.aiadmin.util.JsonFiles;

/**
 * 行动记录（带进下一次的提示词，让代理知道自己刚做过什么）+ 长期记忆摘要（旧记录压缩后的结果）。
 * 存在 &lt;游戏目录&gt;/aiadmin/memory.json：{"digest": "...", "entries": [...]}（兼容旧格式：直接是记录数组）。
 * 不压缩时最多保留 50 条（最旧的直接丢弃）。所有方法加锁。
 */
public final class Memory {
    public record Entry(String time, String trigger, String provider, String summary, List<String> actions, String error) {}

    private static final Gson GSON = new Gson();
    static final int HARD_MAX = 50;

    private static final class State {
        String digest = "";
        List<Entry> entries = new ArrayList<>();
    }

    private final Path file;
    private State st = new State();

    private String loadError;

    /** 记录坏了就从空开始，不影响运行；坏文件改名隔离（见 JsonFiles），不会被下次保存覆盖。 */
    public Memory(Path file) {
        this.file = file;
        JsonFiles.Load ld = JsonFiles.load(file);
        if (!ld.ok()) {
            loadError = ld.error();
            return;
        }
        try {
            if (ld.json() != null) {
                JsonElement e = ld.json();
                if (e.isJsonArray()) {
                    List<Entry> l = GSON.fromJson(e, new TypeToken<List<Entry>>() {}.getType());
                    if (l != null) st.entries.addAll(l);
                } else if (e.isJsonObject()) {
                    State s = GSON.fromJson(e, State.class);
                    if (s != null) {
                        st.digest = s.digest == null ? "" : s.digest;
                        if (s.entries != null) st.entries.addAll(s.entries);
                    }
                }
            }
        } catch (RuntimeException ex) {
            st = new State();
            loadError = JsonFiles.quarantine(file, ex.getMessage());
        }
    }

    public synchronized String loadError() {
        return loadError;
    }

    public synchronized void add(Entry e) {
        st.entries.add(e);
        while (st.entries.size() > HARD_MAX) st.entries.remove(0);
        save();
    }

    public synchronized List<Entry> recent(int n) {
        return new ArrayList<>(st.entries.subList(Math.max(0, st.entries.size() - n), st.entries.size()));
    }

    public synchronized Entry last() {
        return st.entries.isEmpty() ? null : st.entries.get(st.entries.size() - 1);
    }

    public synchronized String digest() {
        return st.digest;
    }

    public synchronized int size() {
        return st.entries.size();
    }

    /** 记录超过 over 条时，返回需要压缩的较旧记录（保留最近 keep 条）；否则空。 */
    public synchronized List<Entry> toCompress(int over, int keep) {
        if (st.entries.size() <= over) return List.of();
        return new ArrayList<>(st.entries.subList(0, st.entries.size() - keep));
    }

    /**
     * 用新摘要替换掉 toCompress 时拿到的那些记录（按对象身份删除，不按条数）。
     * 并发约束：模型写摘要要几十秒，期间云端会从远程线程、定时线程追加记录，超过 HARD_MAX 时 add 还会从头部裁掉最旧的——
     * 以前按"删最旧的 count 条"，头部被裁过就会把还没压缩的记录也删掉。
     */
    public synchronized void applyCompression(List<Entry> compressed, String newDigest) {
        java.util.Set<Entry> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        gone.addAll(compressed);
        st.entries.removeIf(gone::contains);
        st.digest = newDigest == null ? "" : newDigest;
        save();
    }

    private void save() {
        JsonFiles.writeQuietly(file, GSON.toJson(st)); // 写不了就只留在内存（失败经 JsonFiles 上报日志）
    }
}
