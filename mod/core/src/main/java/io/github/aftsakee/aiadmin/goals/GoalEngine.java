package io.github.aftsakee.aiadmin.goals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.gamedata.CraftPlanner;
import io.github.aftsakee.aiadmin.gamedata.GameData;
import io.github.aftsakee.aiadmin.gamedata.GameDataQueries;
import io.github.aftsakee.aiadmin.goals.GoalStore.Personal;
import io.github.aftsakee.aiadmin.goals.GoalTable.Cond;
import io.github.aftsakee.aiadmin.goals.GoalTable.Stage;
import io.github.aftsakee.aiadmin.goals.GoalTable.Table;
import io.github.aftsakee.aiadmin.goals.GoalTable.Target;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 目标判定与提示（纯逻辑，平台层提供玩家事实 {@link Facts}）。
 * <ul>
 *   <li>阶段：已记录完成的永远算完成（掉了东西不会倒退）；当前阶段 = 最后一个已完成主线阶段之后的第一个未完成主线阶段
 *       （所以直接打了龙的老玩家不会被提示去做木镐）；支线（optional）单独列出。</li>
 *   <li>个人目标：背包里数量够了就记完成，并通知本人（可配置全服播报）。</li>
 *   <li>定时提示：模板文字，不调用 AI。频率限制见 goals.json 的 hints。</li>
 * </ul>
 * 调用方负责线程：check/hint 在服务端主线程（Facts 要读玩家数据），status 也可在主线程预先算好再交给其它线程。
 */
public final class GoalEngine {
    /** 一个在线玩家此刻的事实。inventory = 背包 + 盔甲栏 + 副手，按物品 ID 汇总。 */
    public interface Facts {
        String name();

        Map<String, Integer> inventory();

        boolean hasAdvancement(String id);

        /** type = minecraft:used / crafted / mined / killed / picked_up / custom…；不认识的返回 0。 */
        int stat(String type, String key);

        /** 本次上线的时间（毫秒时间戳）。 */
        long onlineSince();
    }

    /** check 的结果：tell = 发给本人的话，announce = 全服播报。 */
    public record News(List<String> tell, List<String> announce) {}

    private final GoalTable table;
    private final GoalStore store;
    private final Supplier<GameData> data;
    private final Predicate<String> modLoaded;

    /**
     * 定时提示的廉价签名：上次算 nextLines 时的全部输入（目标表、游戏资料快照按对象身份比；玩家记录里的阶段/个人目标；整个背包）和结果。
     * 原因：提示间隔到了但内容没变时，旧实现每 30 秒都在主线程重算整条合成路线（CraftPlanner）才发现不用提示。
     * 边界：输入完全相同才复用；planner 只依赖这些输入，所以复用的结果和重算一样。只有主线程的 hint() 读写，用并发 Map 只为防御；
     * 超过 {@link #HINT_MEMO_MAX} 个玩家时整体清空（离线玩家的条目不再有用）。
     */
    private record HintMemo(Table table, GameData data, String record, Map<String, Integer> inventory, List<String> lines) {}

    private static final int HINT_MEMO_MAX = 512;
    private final Map<String, HintMemo> hintMemo = new ConcurrentHashMap<>();

    public GoalEngine(GoalTable table, GoalStore store, Supplier<GameData> data, Predicate<String> modLoaded) {
        this.table = table;
        this.store = store;
        this.data = data;
        this.modLoaded = modLoaded;
    }

    public GoalTable table() {
        return table;
    }

    public GoalStore store() {
        return store;
    }

    public boolean enabled() {
        Table t = table.get();
        return t != null && t.settings().enabled();
    }

    /** 服务器上可用的阶段（去掉 requires_mod 没装的）。 */
    public List<Stage> stages() {
        Table t = table.get();
        if (t == null) return List.of();
        List<Stage> r = new ArrayList<>();
        for (Stage s : t.stages()) if (s.requiresMod().isEmpty() || modLoaded.test(s.requiresMod())) r.add(s);
        return r;
    }

    public static boolean met(Cond c, Facts f, GameData d) {
        return switch (c) {
            case Cond.Item i -> count(i.item(), f.inventory(), d) >= i.count();
            case Cond.Adv a -> f.hasAdvancement(a.id());
            case Cond.Stat s -> f.stat(s.type(), s.key()) >= s.min();
            case Cond.All a -> a.terms().stream().allMatch(x -> met(x, f, d));
            case Cond.Any a -> a.terms().stream().anyMatch(x -> met(x, f, d));
        };
    }

    /** 背包里某物品（或 #标签 里任意物品）的数量。 */
    public static int count(String item, Map<String, Integer> inv, GameData d) {
        if (item.startsWith("#")) {
            if (d == null) return 0;
            int n = 0;
            for (String x : d.tags.getOrDefault(GameData.norm(item), List.of())) n += inv.getOrDefault(x, 0);
            return n;
        }
        return inv.getOrDefault(GameData.norm(item), 0);
    }

    /** 定期检查一个在线玩家：记新完成的阶段和个人目标。 */
    public News check(Facts f, long now) {
        List<String> tell = new ArrayList<>();
        List<String> announce = new ArrayList<>();
        Table t = table.get();
        if (t == null || !t.settings().enabled()) return new News(tell, announce);
        GameData d = data.get();
        GoalStore.Player p = store.get(f.name());
        List<Stage> newly = new ArrayList<>();
        for (Stage s : stages()) if (!p.stagesDone.containsKey(s.id()) && met(s.doneWhen(), f, d)) newly.add(s);
        List<Personal> doneNow = new ArrayList<>();
        for (Personal x : p.personal) if (x.done == 0 && count(x.item, f.inventory(), d) >= x.count) doneNow.add(x);
        if (newly.isEmpty() && doneNow.isEmpty()) return new News(tell, announce);
        store.update(f.name(), q -> {
            for (Stage s : newly) q.stagesDone.putIfAbsent(s.id(), now);
            for (Personal x : q.personal) {
                for (Personal y : doneNow) if (x.done == 0 && x.item.equals(y.item) && x.created == y.created) x.done = now;
            }
        });
        // 第一次检查时可能一次补记很多阶段（老玩家），只提最后一个，避免刷屏
        if (!newly.isEmpty()) {
            Stage last = newly.get(newly.size() - 1);
            tell.add("【目标】达成「" + last.name() + "」" + (newly.size() > 1 ? "（以及之前的 " + (newly.size() - 1) + " 个阶段）" : "") + "！");
            Stage next = current(f.name());
            if (next != null) tell.add("下一步：「" + next.name() + "」" + (next.description().isBlank() ? "" : "——" + next.description()) + "（/goal next 看路线）");
        }
        for (Personal x : doneNow) {
            String what = label(d, x.item) + " ×" + x.count;
            tell.add("【目标】个人目标完成：" + what + "！");
            if (t.settings().announceComplete()) announce.add("【目标】" + f.name() + " 完成了目标：" + what);
        }
        return new News(tell, announce);
    }

    /** 当前主线阶段（null = 全部完成或没有表）。 */
    public Stage current(String player) {
        GoalStore.Player p = store.get(player);
        List<Stage> st = stages();
        int lastDone = -1;
        for (int i = 0; i < st.size(); i++) if (!st.get(i).optional() && p.stagesDone.containsKey(st.get(i).id())) lastDone = i;
        for (int i = lastDone + 1; i < st.size(); i++) if (!st.get(i).optional() && !p.stagesDone.containsKey(st.get(i).id())) return st.get(i);
        return null;
    }

    /** 未完成的支线。 */
    public List<Stage> sideGoals(String player) {
        GoalStore.Player p = store.get(player);
        List<Stage> r = new ArrayList<>();
        for (Stage s : stages()) if (s.optional() && !p.stagesDone.containsKey(s.id())) r.add(s);
        return r;
    }

    /** 某阶段第一个还没备齐的目标物品（都齐了或没有 targets 返回 null）。 */
    public static Target firstUnmet(Stage s, Map<String, Integer> inv, GameData d) {
        for (Target t : s.targets()) if (count(t.item(), inv, d) < t.count()) return t;
        return null;
    }

    /** 给玩家看的"下一步"说明（/goal next、定时提示共用）。 */
    public List<String> nextLines(Facts f, int maxSteps, int maxMissing) {
        GameData d = data.get();
        List<String> lines = new ArrayList<>();
        GoalStore.Player p = store.get(f.name());
        Personal personal = null;
        for (Personal x : GoalStore.ordered(p.personal)) if (x.done == 0) {
            personal = x;
            break;
        }
        if (personal != null) {
            lines.add("【个人目标】" + label(d, personal.item) + " ×" + personal.count + (personal.note == null || personal.note.isBlank() ? "" : "（" + personal.note + "）"));
            lines.addAll(planLines(d, personal.item, personal.count, f.inventory(), maxSteps, maxMissing));
            return lines;
        }
        Stage s = current(f.name());
        if (s == null) {
            List<Stage> side = sideGoals(f.name());
            if (side.isEmpty()) return lines;
            s = side.get(0);
        }
        lines.add("【阶段目标】" + s.name() + (s.description().isBlank() ? "" : "——" + s.description()));
        Target t = firstUnmet(s, f.inventory(), d);
        if (t != null) lines.addAll(planLines(d, t.item(), t.count(), f.inventory(), maxSteps, maxMissing));
        else if (!s.targets().isEmpty()) lines.add("需要的东西都齐了，去完成它吧！");
        return lines;
    }

    private static List<String> planLines(GameData d, String item, int count, Map<String, Integer> inv, int maxSteps, int maxMissing) {
        if (d == null) return List.of("（游戏资料还没加载好，稍后再试）");
        if (item.startsWith("#")) return List.of("需要标签 " + item + " 里的任意物品 ×" + count);
        String id = GameData.norm(item);
        CraftPlanner.Plan plan = CraftPlanner.plan(d, id, count, inv, CraftPlanner.Options.defaults());
        List<String> l = GameDataQueries.planLines(d, plan, maxSteps, maxMissing);
        return l.subList(1, l.size()); // 第一行"目标：…"和上面的标题重复
    }

    /**
     * 定时提示：到时间、开着提示、上线够久、而且"有新内容或很久没提示"时返回要私聊的几行，并记下这次提示；否则返回空列表。
     * 内容签名 = 提示文字本身，文字没变（进度没变化）时按 repeat_unchanged_minutes 降频。
     */
    public List<String> hint(Facts f, long now) {
        Table t = table.get();
        if (t == null || !t.settings().enabled() || !t.settings().hintsEnabled()) return List.of();
        GoalTable.Settings s = t.settings();
        GoalStore.Player p = store.get(f.name());
        if (p.hintsOff) return List.of();
        if (now - f.onlineSince() < s.hintMinOnlineMinutes() * 60_000L) return List.of();
        if (p.lastHint > 0 && now - p.lastHint < s.hintIntervalMinutes() * 60_000L) return List.of();
        List<String> lines = hintLines(f, t, p);
        if (lines.isEmpty()) return List.of();
        String key = String.join("\n", lines);
        if (key.equals(p.lastHintKey) && now - p.lastHint < s.hintRepeatUnchangedMinutes() * 60_000L) return List.of();
        store.update(f.name(), q -> {
            q.lastHint = now;
            q.lastHintKey = key;
        });
        List<String> out = new ArrayList<>(lines);
        out.add("（/goal 看全部目标，/goal hints off 关闭提示）");
        return out;
    }

    /** 提示内容；输入和上次一样时直接复用上次的结果（见 {@link HintMemo}）。 */
    private List<String> hintLines(Facts f, Table t, GoalStore.Player p) {
        GameData d = data.get();
        String record = recordSig(p);
        Map<String, Integer> inv = f.inventory();
        String k = GoalStore.key(f.name());
        HintMemo m = hintMemo.get(k);
        if (m != null && m.table() == t && m.data() == d && m.record().equals(record) && m.inventory().equals(inv)) return m.lines();
        List<String> lines = nextLines(f, 3, t.settings().hintMaxMissing());
        if (hintMemo.size() >= HINT_MEMO_MAX) hintMemo.clear();
        hintMemo.put(k, new HintMemo(t, d, record, new HashMap<>(inv), List.copyOf(lines)));
        return lines;
    }

    /** 玩家记录里影响 nextLines 的部分：已完成阶段、个人目标（物品、数量、备注、完成时间）。 */
    private static String recordSig(GoalStore.Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.stagesDone.keySet());
        for (Personal x : p.personal) sb.append('|').append(x.item).append('*').append(x.count).append('#').append(x.note).append('@').append(x.done);
        return sb.toString();
    }

    /** 给 AI 工具 goal_status 用的完整状态。facts = null 表示玩家不在线（只给记录，不判定背包）。 */
    public JsonObject status(String player, Facts facts) {
        GameData d = data.get();
        JsonObject o = new JsonObject();
        o.addProperty("player", player);
        Table t = table.get();
        if (t == null) {
            o.addProperty("error", table.error() == null ? "目标表没有加载" : table.error());
            return o;
        }
        o.addProperty("enabled", t.settings().enabled());
        if (!t.warnings().isEmpty()) {
            JsonArray w = new JsonArray();
            t.warnings().forEach(w::add);
            o.add("table_warnings", w); // goals.json 里字段类型不对、阶段缺 id 等非致命问题，AI 可以转告服主
        }
        GoalStore.Player p = store.get(player);
        JsonArray stages = new JsonArray();
        for (Stage s : stages()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", s.id());
            x.addProperty("name", s.name());
            if (!s.description().isBlank()) x.addProperty("description", s.description());
            if (s.optional()) x.addProperty("optional", true);
            Long done = p.stagesDone.get(s.id());
            x.addProperty("done", done != null ? Text.time(done) : "未完成");
            if (!s.targets().isEmpty()) {
                JsonArray ts = new JsonArray();
                for (Target tg : s.targets()) {
                    String line = tg.item() + "（" + label(d, tg.item()) + "）×" + tg.count();
                    if (facts != null) line += "，背包里有 " + count(tg.item(), facts.inventory(), d);
                    ts.add(line);
                }
                x.add("targets", ts);
            }
            stages.add(x);
        }
        o.add("stages", stages);
        Stage cur = current(player);
        o.addProperty("current_stage", cur == null ? "主线全部完成" : cur.id() + "（" + cur.name() + "）");
        JsonArray personal = new JsonArray();
        int i = 0;
        for (Personal x : GoalStore.ordered(p.personal)) {
            i++;
            JsonObject y = new JsonObject();
            y.addProperty("index", i);
            y.addProperty("item", x.item + "（" + label(d, x.item) + "）");
            y.addProperty("count", x.count);
            if (x.note != null && !x.note.isBlank()) y.addProperty("note", x.note);
            if (facts != null && x.done == 0) y.addProperty("have", count(x.item, facts.inventory(), d));
            y.addProperty("status", x.done == 0 ? "进行中" : "已完成 " + Text.time(x.done));
            if (x.setBy != null && !x.setBy.equals(player)) y.addProperty("set_by", x.setBy);
            personal.add(y);
        }
        o.add("personal_goals", personal);
        o.addProperty("hints", p.hintsOff ? "玩家已关闭定时提示" : t.settings().hintsEnabled() ? "开（每 " + t.settings().hintIntervalMinutes() + " 分钟最多一条）" : "服主已关闭");
        if (p.lastHint > 0) o.addProperty("last_hint", Text.time(p.lastHint));
        if (facts == null) o.addProperty("note", "玩家不在线，背包数量没有统计");
        else {
            JsonArray next = new JsonArray();
            for (String l : nextLines(facts, 6, 6)) next.add(l);
            o.add("next_suggestion", next);
        }
        return o;
    }

    static String label(GameData d, String item) {
        if (item.startsWith("#")) return item;
        return d == null ? item : d.label(GameData.norm(item));
    }
}
