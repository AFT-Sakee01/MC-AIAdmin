package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "第一次"：每名玩家第一次造出某台机器 / 捡到某样稀有物品，以及全服第一次。用来把"有意义的行为"挑出来，
 * 而不是每开一次箱子、挖一个方块都推给 AI。
 * <p>持久化 aiadmin/milestones.json（变化时原子写入；这类事件很少，不需要合并写入）。每名玩家最多记 3000 项，全服 5000 项。
 */
public final class Milestones {
    static final int PER_PLAYER = 3000;
    static final int SERVER = 5000;
    private final Path file;
    private final Map<String, Set<String>> players = new HashMap<>();
    private final Set<String> server = new LinkedHashSet<>();
    private String error;

    public Milestones(Path file) {
        this.file = file;
        load();
    }

    /** 这名玩家第一次达成 key：返回 true 并记下。 */
    public synchronized boolean first(String player, String key) {
        Set<String> s = players.computeIfAbsent(player.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>());
        if (s.contains(key)) return false;
        if (s.size() >= PER_PLAYER) s.remove(s.iterator().next());
        s.add(key);
        dirty = true; // 主线程上调用：不在这里写盘，由定时器 saveIfDirty（以前每次都同步重写整个文件）
        return true;
    }

    /** 全服第一次：返回 true 并记下。 */
    public synchronized boolean serverFirst(String key) {
        if (server.contains(key)) return false;
        if (server.size() >= SERVER) server.remove(server.iterator().next());
        server.add(key);
        dirty = true;
        return true;
    }

    public synchronized String error() {
        return error;
    }

    /** 定时器（AgentService.tick）和关服时调用。数据量小（每人最多 PER_PLAYER 条），锁内序列化无妨。 */
    public synchronized void saveIfDirty() {
        if (dirty) save();
    }

    /** 读坏不覆盖（见 JsonFiles）：读不了的话原文件被隔离，否则所有"第一次"会在下次保存时被清空、重新触发。 */
    private void load() {
        if (file == null) return;
        JsonFiles.Load l = JsonFiles.load(file);
        if (!l.ok()) {
            error = l.error();
            return;
        }
        if (l.json() == null) return;
        try {
            JsonObject o = l.json().getAsJsonObject();
            if (o.has("server")) for (JsonElement x : o.getAsJsonArray("server")) server.add(x.getAsString());
            if (o.has("players")) {
                for (Map.Entry<String, JsonElement> p : o.getAsJsonObject("players").entrySet()) {
                    Set<String> s = new LinkedHashSet<>();
                    for (JsonElement x : p.getValue().getAsJsonArray()) s.add(x.getAsString());
                    players.put(p.getKey(), s);
                }
            }
        } catch (RuntimeException ex) {
            server.clear();
            players.clear();
            error = JsonFiles.quarantine(file, ex.getMessage());
        }
    }

    private boolean dirty;

    private void save() {
        dirty = false;
        if (file == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 1);
        JsonArray s = new JsonArray();
        server.forEach(s::add);
        o.add("server", s);
        JsonObject p = new JsonObject();
        for (Map.Entry<String, Set<String>> e : players.entrySet()) {
            JsonArray a = new JsonArray();
            e.getValue().forEach(a::add);
            p.add(e.getKey(), a);
        }
        o.add("players", p);
        String err = JsonFiles.writeQuietly(file, o.toString());
        if (err != null) {
            error = err;
            dirty = true;
        }
    }
}
