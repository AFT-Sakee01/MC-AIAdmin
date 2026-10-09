package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 战斗的前因后果：每名玩家最近 60 秒挨的打（谁打的、用什么、打了多少、打完剩多少血），死亡时整理成"死前 30 秒"的记录，
 * 附上附近队友、掉落件数；之后跟踪他有没有回去捡（死亡点 8 格内捡到东西 = 取回；5 分钟后掉落物会消失 = 没捡回）。
 * 另提供"救下队友"的判断：A 击杀的生物在 8 秒内打过 B、且 B 当时血量不到一半。
 * <p>只在内存里（重启清空）：每人最近 10 次死亡，挨打记录最多 60 秒 / 40 条。线程：服务端主线程写、工具线程读，同一把锁。
 */
public final class CombatLog {
    /** 一次挨打。attackerUuid 用来判断"救下队友"；cause 是伤害类型（mob、arrow、lava、fall…）。 */
    public record Hit(long time, float amount, String cause, String attacker, String attackerName, String attackerUuid, boolean attackerPlayer,
                      String weapon, float healthAfter, float maxHealth) {}

    /** 死亡时附近的玩家：距离、血量、最近 30 秒是否也在挨打。 */
    public record Nearby(String name, int distance, float health, boolean hurtRecently) {}

    public static final class Death {
        public long time;
        public String player;
        public String message;
        public String dim;
        public int x;
        public int y;
        public int z;
        public List<Hit> hits = new ArrayList<>();
        public List<Nearby> nearby = new ArrayList<>();
        /** -1 = 还不知道（掉落事件在死亡事件之后）。 */
        public int drops = -1;
        public boolean keepInventory;
        /** pending 等他回去捡 / retrieved 捡回了 / lost 没捡回（掉落物已消失）/ grave 没有掉落物（多半被墓碑类模组收走）/ none 死亡不掉落。 */
        public String retrieval = "pending";
        public int picked;
        public long resolved;
    }

    static final long HIT_KEEP_MS = 60_000L;
    static final long STORY_MS = 30_000L;
    static final long RESCUE_MS = 8_000L;
    static final long DESPAWN_MS = 5 * 60_000L + 30_000L;
    static final int PICKUP_RADIUS = 8;

    private final Map<String, ArrayDeque<Hit>> hits = new HashMap<>();
    private final Map<String, ArrayDeque<Death>> deaths = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();

    static String key(String n) {
        return n == null ? "" : n.toLowerCase(Locale.ROOT);
    }

    public synchronized void hurt(String player, Hit h) {
        names.put(key(player), player);
        if (names.size() > 2000) names.clear();
        ArrayDeque<Hit> d = hits.computeIfAbsent(key(player), k -> new ArrayDeque<>());
        d.addLast(h);
        while (d.size() > 40 || (!d.isEmpty() && h.time() - d.peekFirst().time() > HIT_KEEP_MS)) d.pollFirst();
        if (hits.size() > 500) hits.entrySet().removeIf(e -> e.getValue().isEmpty() || h.time() - e.getValue().peekLast().time() > HIT_KEEP_MS);
    }

    /** 最近 30 秒有没有挨打。 */
    public synchronized boolean hurtRecently(String player, long now) {
        ArrayDeque<Hit> d = hits.get(key(player));
        return d != null && !d.isEmpty() && now - d.peekLast().time() <= STORY_MS;
    }

    /** 记一次死亡（死亡事件里调用）：带上死前 30 秒的挨打记录。 */
    public synchronized Death death(String player, String message, String dim, int x, int y, int z, List<Nearby> nearby, boolean keepInventory, long now) {
        Death d = new Death();
        d.time = now;
        d.player = player;
        d.message = message;
        d.dim = dim;
        d.x = x;
        d.y = y;
        d.z = z;
        ArrayDeque<Hit> h = hits.remove(key(player));
        if (h != null) for (Hit x1 : h) if (now - x1.time() <= STORY_MS) d.hits.add(x1);
        d.nearby = List.copyOf(nearby);
        d.keepInventory = keepInventory;
        if (keepInventory) {
            d.retrieval = "none";
            d.drops = 0;
        }
        ArrayDeque<Death> list = deaths.computeIfAbsent(key(player), k -> new ArrayDeque<>());
        list.addLast(d);
        while (list.size() > 10) list.pollFirst();
        if (deaths.size() > 500) deaths.remove(deaths.keySet().iterator().next());
        return d;
    }

    /** 掉落件数（掉落事件里调用，紧跟在死亡之后）。0 件且没开死亡不掉落 = 多半被墓碑类模组收走了。 */
    public synchronized void drops(String player, int count, long now) {
        Death d = last(player);
        if (d == null || now - d.time > 5_000L || d.keepInventory) return;
        d.drops = count;
        if (count == 0) d.retrieval = "grave";
    }

    /**
     * 捡到东西（拾取事件里调用）：离最近一次还在等的死亡点 8 格以内就算捡回。第一次捡回时返回那次死亡（调用方发跟进事件），否则 null。
     */
    public synchronized Death pickup(String player, String dim, double x, double y, double z, int count, long now) {
        Death d = last(player);
        if (d == null || !"pending".equals(d.retrieval) || now - d.time > DESPAWN_MS || !d.dim.equals(dim)) return null;
        if (Math.abs(x - d.x) > PICKUP_RADIUS || Math.abs(y - d.y) > PICKUP_RADIUS || Math.abs(z - d.z) > PICKUP_RADIUS) return null;
        boolean first = d.picked == 0;
        d.picked += count;
        if (first) {
            d.retrieval = "retrieved";
            d.resolved = now;
            return d;
        }
        return null;
    }

    /** 已经标成 retrieved 的死亡继续累计捡回件数（同一地点后续拾取）。 */
    public synchronized void morePickup(String player, String dim, double x, double y, double z, int count, long now) {
        Death d = last(player);
        if (d == null || !"retrieved".equals(d.retrieval) || now - d.time > DESPAWN_MS || !d.dim.equals(dim)) return;
        if (Math.abs(x - d.x) <= PICKUP_RADIUS && Math.abs(y - d.y) <= PICKUP_RADIUS && Math.abs(z - d.z) <= PICKUP_RADIUS) d.picked += count;
    }

    /** 定时调用：掉落物已经消失还没回去捡的，标成 lost 并返回（调用方发跟进事件）。 */
    public synchronized List<Death> expire(long now) {
        List<Death> r = new ArrayList<>();
        for (ArrayDeque<Death> list : deaths.values()) {
            for (Death d : list) {
                if ("pending".equals(d.retrieval) && now - d.time > DESPAWN_MS) {
                    d.retrieval = d.drops == 0 ? "grave" : "lost";
                    d.resolved = now;
                    if ("lost".equals(d.retrieval)) r.add(d);
                }
            }
        }
        return r;
    }

    /**
     * 救下队友：killer 击杀的生物（victimUuid）在 8 秒内打过别的玩家，且那名玩家当时血量不到一半。返回被救的玩家和他当时的血量说明。
     */
    public synchronized List<String> rescued(String killer, String victimUuid, long now) {
        List<String> r = new ArrayList<>();
        if (victimUuid == null) return r;
        for (Map.Entry<String, ArrayDeque<Hit>> e : hits.entrySet()) {
            if (e.getKey().equals(key(killer))) continue;
            Hit worst = null;
            for (Hit h : e.getValue()) {
                if (now - h.time() <= RESCUE_MS && victimUuid.equals(h.attackerUuid()) && (worst == null || h.healthAfter() < worst.healthAfter())) worst = h;
            }
            if (worst != null && worst.healthAfter() > 0 && worst.healthAfter() <= worst.maxHealth() / 2) {
                r.add(names.getOrDefault(e.getKey(), e.getKey()) + "（当时剩 " + Math.round(worst.healthAfter()) + "/" + Math.round(worst.maxHealth()) + " 血）");
            }
        }
        return r;
    }

    private Death last(String player) {
        ArrayDeque<Death> list = deaths.get(key(player));
        return list == null || list.isEmpty() ? null : list.peekLast();
    }

    /** 一次死亡的前因后果，一段话（给事件和工具用）。 */
    public static String story(Death d) {
        StringBuilder sb = new StringBuilder();
        if (d.hits.isEmpty()) {
            sb.append("死前 30 秒没有记录到受击（可能是摔落、溺水、窒息等一次致死，或伤害来源没有经过受伤事件）");
        } else {
            float total = 0;
            Map<String, float[]> by = new LinkedHashMap<>(); // 来源 → {次数, 伤害}
            for (Hit h : d.hits) {
                total += h.amount();
                float[] a = by.computeIfAbsent(label(h), k -> new float[2]);
                a[0]++;
                a[1] += h.amount();
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, float[]> e : by.entrySet()) {
                parts.add(e.getKey() + " ×" + (int) e.getValue()[0] + "（" + Math.round(e.getValue()[1]) + " 点）");
            }
            Hit lastHit = d.hits.get(d.hits.size() - 1);
            sb.append("死前 30 秒挨了 ").append(d.hits.size()).append(" 下共 ").append(Math.round(total)).append(" 点：").append(String.join("、", parts))
                    .append("；最后一击：").append(label(lastHit)).append(lastHit.weapon() != null ? "（" + lastHit.weapon() + "）" : "");
        }
        if (d.nearby.isEmpty()) {
            sb.append("；附近 32 格内没有别的玩家");
        } else {
            List<String> n = new ArrayList<>();
            for (Nearby x : d.nearby) {
                n.add(x.name() + " " + x.distance() + " 格" + (x.hurtRecently() ? "（也在挨打，剩 " + Math.round(x.health()) + " 血）" : ""));
            }
            sb.append("；附近：").append(String.join("、", n));
            if (d.nearby.stream().anyMatch(Nearby::hurtRecently)) sb.append("（组队一起挨打）");
        }
        sb.append("；").append(switch (d.retrieval) {
            case "none" -> "开了死亡不掉落，东西都在身上";
            case "grave" -> "没有掉落物（多半被墓碑类模组收起来了）";
            case "retrieved" -> "已回去捡回 " + d.picked + " 件" + (d.drops > 0 ? "（掉了 " + d.drops + " 件）" : "");
            case "lost" -> "没回去捡，掉落的 " + Math.max(d.drops, 0) + " 件大概已经消失";
            default -> (d.drops > 0 ? "掉了 " + d.drops + " 件在 " : "东西掉在 ") + d.dim + " " + d.x + " " + d.y + " " + d.z + "，5 分钟内回去能捡";
        });
        return sb.toString();
    }

    static String label(Hit h) {
        if (h.attacker() == null) return h.cause();
        String n = PlayerTracker.shortId(h.attacker());
        if (h.attackerPlayer()) return "玩家 " + h.attackerName();
        return h.attackerName() != null ? n + "「" + h.attackerName() + "」" : n;
    }

    public synchronized JsonArray json(String player, int n) {
        JsonArray a = new JsonArray();
        ArrayDeque<Death> list = deaths.get(key(player));
        if (list == null) return a;
        List<Death> l = new ArrayList<>(list);
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) {
            Death d = l.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("time", Text.time(d.time));
            o.addProperty("message", d.message);
            o.addProperty("where", d.dim + " " + d.x + " " + d.y + " " + d.z);
            o.addProperty("story", story(d));
            o.addProperty("retrieval", d.retrieval);
            JsonArray hs = new JsonArray();
            for (Hit h : d.hits) {
                JsonObject x = new JsonObject();
                x.addProperty("seconds_before_death", Math.round((d.time - h.time()) / 100.0) / 10.0);
                x.addProperty("source", label(h));
                x.addProperty("cause", h.cause());
                if (h.weapon() != null) x.addProperty("weapon", h.weapon());
                x.addProperty("damage", Math.round(h.amount() * 10) / 10.0);
                x.addProperty("health_after", Math.round(h.healthAfter() * 10) / 10.0);
                hs.add(x);
            }
            o.add("hits", hs);
            a.add(o);
        }
        return a;
    }
}
