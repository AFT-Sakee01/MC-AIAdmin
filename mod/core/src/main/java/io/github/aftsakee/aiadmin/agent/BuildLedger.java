package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.JsonFiles;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 建筑账本：按区块记"谁在这里放过多少方块"，用来回答"这栋房子是谁的"。服务器没有领地插件，所以只能按放方块的多少推断，
 * 结果是参考（从装上这个版本起才开始记；被别人拆掉的不扣）。
 * <p>每个区块最多记 8 名玩家（超出时去掉放得最少的），最多 30000 个区块（超出时清掉最久没人动的 10%）。
 * 持久化 aiadmin/build-ledger.json：只标脏，由定时器每 5 分钟和关服时写（{@link #saveIfDirty}）。线程：同一把锁。
 */
public final class BuildLedger {
    static final int PER_CHUNK = 8;
    static final int MAX_CHUNKS = 30000;

    private static final class Chunk {
        final Map<String, long[]> by = new LinkedHashMap<>(); // 玩家 → {放的方块数, 最后一次时间}
        long last;
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Chunk> chunks = new HashMap<>();
    private boolean dirty;
    private String error;

    public BuildLedger(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    static String key(String dim, int cx, int cz) {
        return dim + "|" + cx + "|" + cz;
    }

    public synchronized void place(String player, String dim, int x, int z) {
        long now = clock.getAsLong();
        Chunk c = chunks.computeIfAbsent(key(dim, x >> 4, z >> 4), k -> new Chunk());
        long[] a = c.by.get(player);
        if (a == null) {
            // 名字不分大小写：同一区块里已有别的写法就记到它名下（每个区块最多 PER_CHUNK 人，线性找无妨）
            for (Map.Entry<String, long[]> e : c.by.entrySet()) {
                if (e.getKey().equalsIgnoreCase(player)) {
                    a = e.getValue();
                    break;
                }
            }
        }
        if (a == null) {
            if (c.by.size() >= PER_CHUNK) {
                String least = null;
                long min = Long.MAX_VALUE;
                for (Map.Entry<String, long[]> e : c.by.entrySet()) {
                    if (e.getValue()[0] < min) {
                        min = e.getValue()[0];
                        least = e.getKey();
                    }
                }
                c.by.remove(least);
            }
            a = new long[2];
            c.by.put(player, a);
        }
        a[0]++;
        a[1] = now;
        c.last = now;
        dirty = true;
        if (chunks.size() > MAX_CHUNKS) evict();
    }

    private void evict() {
        List<Map.Entry<String, Chunk>> l = new ArrayList<>(chunks.entrySet());
        l.sort((x, y) -> Long.compare(x.getValue().last, y.getValue().last));
        for (int i = 0; i < l.size() / 10; i++) chunks.remove(l.get(i).getKey());
    }

    /** 以 (x, z) 所在区块为中心、半径 r 个区块内，谁放的方块最多（从多到少）。 */
    public synchronized JsonObject who(String dim, int x, int z, int r) {
        Map<String, long[]> sum = new HashMap<>();
        int cx = x >> 4;
        int cz = z >> 4;
        long center = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Chunk c = chunks.get(key(dim, cx + dx, cz + dz));
                if (c == null) continue;
                for (Map.Entry<String, long[]> e : c.by.entrySet()) {
                    long[] s = sum.computeIfAbsent(e.getKey(), k -> new long[2]);
                    s[0] += e.getValue()[0];
                    s[1] = Math.max(s[1], e.getValue()[1]);
                    if (dx == 0 && dz == 0) center += e.getValue()[0];
                }
            }
        }
        List<Map.Entry<String, long[]>> l = new ArrayList<>(sum.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        long total = l.stream().mapToLong(e -> e.getValue()[0]).sum();
        JsonObject o = new JsonObject();
        o.addProperty("dim", dim);
        o.addProperty("center", x + " " + z);
        o.addProperty("radius_chunks", r);
        o.addProperty("blocks_placed_total", total);
        o.addProperty("blocks_placed_center_chunk", center);
        JsonArray a = new JsonArray();
        for (int i = 0; i < l.size() && i < 8; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("player", l.get(i).getKey());
            p.addProperty("blocks", l.get(i).getValue()[0]);
            p.addProperty("share", total == 0 ? 0 : Math.round(l.get(i).getValue()[0] * 1000.0 / total) / 10.0);
            p.addProperty("last_placed", Text.time(l.get(i).getValue()[1]));
            a.add(p);
        }
        o.add("builders", a);
        o.addProperty("note", total == 0 ? "这一带没有记录到玩家放方块（账本从 1.0.0.22 起才开始记，更早的建筑查不到）"
                : "按放方块的多少推断，只是参考：账本从 1.0.0.22 起才开始记，被拆掉的不扣，搬运/铺路也算");
        return o;
    }

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized String error() {
        return error;
    }

    /**
     * 落盘（定时线程每 5 分钟、关服时）。并发约束：锁里只把数据拷一份（几万个区块也只要几毫秒），序列化和写文件在锁外——
     * 主线程每放一个方块都要拿这把锁，以前在锁里序列化几 MB 的 JSON 再写盘，Steam Deck 存储慢时会卡 tick。
     * saveLock 保证两个落盘不会交错写同一个文件（JsonFiles 的临时文件名是唯一的，交错也不会坏文件，只是没必要）。
     */
    public void saveIfDirty() {
        if (file == null) return;
        Map<String, long[][]> snap = new java.util.LinkedHashMap<>();
        Map<String, Long> lasts = new java.util.HashMap<>();
        List<String> names = new ArrayList<>();
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            for (Map.Entry<String, Chunk> e : chunks.entrySet()) {
                long[][] rows = new long[e.getValue().by.size()][];
                int i = 0;
                for (Map.Entry<String, long[]> b : e.getValue().by.entrySet()) {
                    names.add(b.getKey());
                    rows[i++] = new long[] {b.getValue()[0], b.getValue()[1], names.size() - 1};
                }
                snap.put(e.getKey(), rows);
                lasts.put(e.getKey(), e.getValue().last);
            }
        }
        synchronized (saveLock) {
            JsonObject o = new JsonObject();
            o.addProperty("schema_version", 1);
            JsonObject cs = new JsonObject();
            for (Map.Entry<String, long[][]> e : snap.entrySet()) {
                JsonObject c = new JsonObject();
                c.addProperty("last", lasts.get(e.getKey()));
                JsonObject by = new JsonObject();
                for (long[] row : e.getValue()) {
                    JsonArray v = new JsonArray();
                    v.add(row[0]);
                    v.add(row[1]);
                    by.add(names.get((int) row[2]), v);
                }
                c.add("by", by);
                cs.add(e.getKey(), c);
            }
            o.add("chunks", cs);
            String err = JsonFiles.writeQuietly(file, o.toString());
            synchronized (this) {
                if (err != null) {
                    error = err;
                    dirty = true; // 下次再试
                }
            }
        }
    }

    private final Object saveLock = new Object();

    /** 读坏不覆盖（见 JsonFiles）；个别区块坏了跳过。 */
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
        JsonElement ce = l.json().getAsJsonObject().get("chunks");
        if (ce == null || !ce.isJsonObject()) return;
        int bad = 0;
        for (Map.Entry<String, JsonElement> x : ce.getAsJsonObject().entrySet()) {
            try {
                JsonObject c = x.getValue().getAsJsonObject();
                Chunk ch = new Chunk();
                ch.last = c.has("last") ? c.get("last").getAsLong() : 0;
                for (Map.Entry<String, JsonElement> b : c.getAsJsonObject("by").entrySet()) {
                    JsonArray v = b.getValue().getAsJsonArray();
                    ch.by.put(b.getKey(), new long[]{v.get(0).getAsLong(), v.get(1).getAsLong()});
                }
                chunks.put(x.getKey(), ch);
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (bad > 0) error = JsonFiles.keepBadCopy(file, bad);
    }
}
