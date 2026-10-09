package io.github.aftsakee.aiadmin.tpa;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.aftsakee.aiadmin.config.BotConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 传送请求的状态（与 MC 无关，可单测）：待处理的请求、冷却、每名玩家的自动接受设置。
 * <ul>
 *   <li>请求按"接收者 → (发起者 → 请求)"保存；同一发起者对同一接收者的新请求替换旧的。接受/拒绝默认处理最新的一条，也可指定发起者。</li>
 *   <li>冷却按"玩家 + 指令种类"记录发出时间。shared 模式取两种指令里较晚的一次、按 seconds 计算；独立模式各算各的。
 *       这样运行中切换 shared 开关也不会丢冷却。冷却只在内存里，重启清空。</li>
 *   <li>自动接受（只对 /tpa 生效）按 UUID 存到 &lt;游戏目录&gt;/aiadmin/tpa-prefs.json，原子写入。</li>
 * </ul>
 * 只在服务端主线程使用（指令和 tick），方法仍加锁，防止以后从别的线程调用。
 */
public final class TpaManager {
    public enum Kind { TPA, TPAH }

    /** from 发起；TPA = from 传送到 to 那里；TPAH = 把 to 传送到 from 那里。 */
    public record Request(UUID from, String fromName, UUID to, String toName, Kind kind, long created) {}

    private static final Gson GSON = new Gson();
    private final Path prefsFile;
    private final Map<UUID, LinkedHashMap<UUID, Request>> pending = new HashMap<>();
    private final Map<UUID, EnumMap<Kind, Long>> lastSent = new HashMap<>();
    private final Map<String, String> prefs = new LinkedHashMap<>(); // uuid -> "auto"

    public TpaManager(Path prefsFile) {
        this.prefsFile = prefsFile;
        // 设置坏了就当全部手动；坏文件改名隔离（见 JsonFiles），之后第一次 /tpa auto 不会用只有一条的内容覆盖掉别人的设置
        io.github.aftsakee.aiadmin.util.JsonFiles.Load l = io.github.aftsakee.aiadmin.util.JsonFiles.load(prefsFile);
        if (l.ok() && l.json() != null) {
            try {
                Map<String, String> m = GSON.fromJson(l.json(), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) prefs.putAll(m);
            } catch (RuntimeException e) {
                io.github.aftsakee.aiadmin.util.JsonFiles.quarantine(prefsFile, e.getMessage());
            }
        }
    }

    // ———————————————————— 冷却 ————————————————————

    /** 还要等多少毫秒才能再发这种请求（0 = 可以发）。 */
    public synchronized long cooldownLeft(UUID player, Kind kind, BotConfig.TpaCooldown cfg, long now) {
        EnumMap<Kind, Long> m = lastSent.get(player);
        if (m == null) return 0;
        Long last;
        long seconds;
        if (cfg.shared) {
            Long t1 = m.get(Kind.TPA), t2 = m.get(Kind.TPAH);
            last = t1 == null ? t2 : t2 == null ? t1 : Long.valueOf(Math.max(t1, t2));
            seconds = cfg.seconds;
        } else {
            last = m.get(kind);
            seconds = kind == Kind.TPA ? cfg.tpaSeconds : cfg.tpahSeconds;
        }
        if (last == null) return 0; // 从没发过这种请求
        return Math.max(0, last + seconds * 1000L - now);
    }

    /** 请求发出即进入冷却。 */
    public synchronized void startCooldown(UUID player, Kind kind, long now) {
        lastSent.computeIfAbsent(player, k -> new EnumMap<>(Kind.class)).put(kind, now);
    }

    // ———————————————————— 请求 ————————————————————

    public synchronized void put(Request r) {
        LinkedHashMap<UUID, Request> m = pending.computeIfAbsent(r.to(), k -> new LinkedHashMap<>());
        m.remove(r.from()); // 重新插入到末尾 = 最新
        m.put(r.from(), r);
    }

    /**
     * 取出（并移除）接收者的一条有效请求：fromName 为 null 取最新的一条，否则按发起者名字（不分大小写）找。
     * 已过期的请求顺便清掉、不会被取到。
     */
    public synchronized Request take(UUID to, String fromName, long now, int timeoutSeconds) {
        LinkedHashMap<UUID, Request> m = pending.get(to);
        if (m == null) return null;
        m.values().removeIf(r -> expired(r, now, timeoutSeconds));
        Request found = null;
        for (Request r : m.values()) {
            if (fromName == null || r.fromName().equalsIgnoreCase(fromName)) found = r; // 遍历到最后 = 最新
        }
        if (found != null) m.remove(found.from());
        if (m.isEmpty()) pending.remove(to);
        return found;
    }

    public synchronized List<Request> pendingFor(UUID to, long now, int timeoutSeconds) {
        List<Request> out = new ArrayList<>();
        LinkedHashMap<UUID, Request> m = pending.get(to);
        if (m != null) for (Request r : m.values()) if (!expired(r, now, timeoutSeconds)) out.add(r);
        return out;
    }

    /** 移除并返回所有过期的请求（由 tick 调用，用来通知接收者）。 */
    public synchronized List<Request> expire(long now, int timeoutSeconds) {
        List<Request> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Request>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            LinkedHashMap<UUID, Request> m = it.next().getValue();
            for (Iterator<Request> ri = m.values().iterator(); ri.hasNext(); ) {
                Request r = ri.next();
                if (expired(r, now, timeoutSeconds)) {
                    out.add(r);
                    ri.remove();
                }
            }
            if (m.isEmpty()) it.remove();
        }
        return out;
    }

    /** 玩家下线：清掉他发出的和收到的所有请求（静默）。 */
    public synchronized void dropPlayer(UUID player) {
        pending.remove(player);
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Request>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            LinkedHashMap<UUID, Request> m = it.next().getValue();
            m.remove(player);
            if (m.isEmpty()) it.remove();
        }
    }

    private static boolean expired(Request r, long now, int timeoutSeconds) {
        return now - r.created() > timeoutSeconds * 1000L;
    }

    // ———————————————————— 自动接受 ————————————————————

    public synchronized boolean autoAccept(UUID player) {
        return "auto".equals(prefs.get(player.toString()));
    }

    public synchronized void setAutoAccept(UUID player, boolean auto) {
        if (auto) prefs.put(player.toString(), "auto");
        else prefs.remove(player.toString()); // 默认就是手动，不必存
        if (prefsFile == null) return;
        io.github.aftsakee.aiadmin.util.JsonFiles.writeQuietly(prefsFile, GSON.toJson(prefs)); // 写不了就只在本次开服内有效
    }

    /** "3 分 12 秒"。 */
    public static String fmt(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 60 ? (s / 60) + " 分 " + (s % 60) + " 秒" : s + " 秒";
    }
}
