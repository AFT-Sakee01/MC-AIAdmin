package io.github.aftsakee.aiadmin.goals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.util.Json;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * 目标表 config/aiadmin/goals.json：服主写的"阶段进度表"+ 玩家个人目标和定时提示的设置。
 * 文件不存在时按 {@link #DEFAULT} 生成；之后每次检查时按修改时间自动重读（不需要 /aibot reload），
 * 读失败时保留上一次成功读到的内容（首次就失败则整个目标功能停用），错误通过 {@link #error()} 报告。
 * <p>主线程约束（项目规则：服务端主线程上不做文件 IO）：平台层用 {@link #offMainThread} 接上"是否主线程"和一个后台执行器后，
 * 在主线程上调用 {@link #get()} 只返回已读到的表，"看看文件改了没有"排到后台执行器上（同一时间最多排一个），
 * 服主改完保存后最多晚一轮检查生效；其他线程（AI 工具）和没接执行器时（测试）照旧在调用线程上检查。
 * 查修改时间和读文件都在本对象的锁外做，锁里只换表，主线程不会因为后台在读文件而等锁。
 * <p>完成条件（done_when）写法：
 * <ul>
 *   <li>{"item": "minecraft:iron_ingot", "count": 3} —— 背包（含盔甲栏、副手）里有至少 count 个；item 可写 "#标签"</li>
 *   <li>{"advancement": "minecraft:story/smelt_iron"} —— 已取得这个进度</li>
 *   <li>{"stat": "minecraft:used", "key": "minecolonies:blockhuttownhall", "min": 1} —— 统计值（used/crafted/mined/killed/picked_up/custom）</li>
 *   <li>{"all": [ … ]} / {"any": [ … ]} —— 组合</li>
 * </ul>
 * 阶段按列表顺序推进；optional = true 的是支线（不挡主线，单独列出）；requires_mod 填了而服务器没装这个模组时整条跳过。
 * <p>字段类型写错（"enabled": "false"、"count": "3"）不让整份表读不了，也不静默取默认：能看出意思的（字符串 "true"/"false"、
 * 纯数字字符串）按意思理解，其余取默认值，两种情况都记进 {@link Table#warnings}（{@link #warnings()}），由平台层写日志、goal_status 带给 AI。
 * 结构性错误（阶段不是对象、缺 done_when、id 重复、条件不认识）仍然是解析错误，整份保留上次的内容。
 */
public final class GoalTable {
    public sealed interface Cond permits Cond.Item, Cond.Adv, Cond.Stat, Cond.All, Cond.Any {
        record Item(String item, int count) implements Cond {}

        record Adv(String id) implements Cond {}

        record Stat(String type, String key, int min) implements Cond {}

        record All(List<Cond> terms) implements Cond {}

        record Any(List<Cond> terms) implements Cond {}
    }

    /** targets = 这个阶段要准备的东西（用来算合成链路和提示还缺什么）；可以为空（纯进度型目标，比如"进入下界"）。 */
    public record Target(String item, int count) {}

    public record Stage(String id, String name, String description, List<Target> targets, Cond doneWhen, boolean optional, String requiresMod) {}

    public record Settings(boolean enabled, int checkIntervalSeconds, boolean hintsEnabled, int hintIntervalMinutes, int hintMinOnlineMinutes,
                           int hintRepeatUnchangedMinutes, int hintMaxMissing, int personalMax, boolean announceComplete) {}

    /** warnings = 读表时发现的非致命问题（字段类型不对、阶段缺 id…），给服主看。 */
    public record Table(Settings settings, List<Stage> stages, List<String> warnings) {
        public Table {
            stages = List.copyOf(stages);
            warnings = List.copyOf(warnings);
        }

        public Table(Settings settings, List<Stage> stages) {
            this(settings, stages, List.of());
        }
    }

    public static final String DEFAULT = """
            {
              "_说明": "目标表。enabled=false 关闭整个目标功能（/goal 指令、自动检查、定时提示）。改完保存即生效（按修改时间自动重读）。条件写法见 README 的「目标表」一节。",
              "enabled": true,
              "check_interval_seconds": 30,
              "hints": {
                "_说明": "定时私聊提示：由模组按模板生成，不调用 AI、不消耗额度。每人最多每 interval_minutes 分钟一条；上线不满 min_online_minutes 分钟不提示；进度没变化时至少隔 repeat_unchanged_minutes 分钟才重复。玩家可以 /goal hints off 自己关掉。",
                "enabled": true,
                "interval_minutes": 20,
                "min_online_minutes": 5,
                "repeat_unchanged_minutes": 60,
                "max_missing_shown": 3
              },
              "personal": {
                "_说明": "玩家个人目标（/goal add 或让 AI 记下来）。max_per_player = 每人最多几个未完成目标；announce_complete = 完成时是否全服播报（否则只告诉本人）。",
                "max_per_player": 10,
                "announce_complete": false
              },
              "stages": [
                {"id": "wood", "name": "木器时代", "description": "砍树，做工作台和木镐",
                 "targets": [{"item": "minecraft:wooden_pickaxe", "count": 1}],
                 "done_when": {"any": [{"advancement": "minecraft:story/mine_stone"}, {"item": "minecraft:wooden_pickaxe"}]}},
                {"id": "stone", "name": "石器时代", "description": "挖圆石，换石镐、做熔炉",
                 "targets": [{"item": "minecraft:stone_pickaxe", "count": 1}, {"item": "minecraft:furnace", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/upgrade_tools"}},
                {"id": "iron", "name": "来硬的", "description": "挖铁矿，用熔炉烧出铁锭",
                 "targets": [{"item": "minecraft:iron_ingot", "count": 3}],
                 "done_when": {"advancement": "minecraft:story/smelt_iron"}},
                {"id": "iron_pick", "name": "铁镐", "description": "做一把铁镐，才能挖钻石",
                 "targets": [{"item": "minecraft:iron_pickaxe", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/iron_tools"}},
                {"id": "armor", "name": "整装上阵", "description": "穿上一件铁盔甲",
                 "targets": [{"item": "minecraft:iron_chestplate", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/obtain_armor"}},
                {"id": "diamond", "name": "钻石！", "description": "到 Y=-58 附近找钻石",
                 "targets": [{"item": "minecraft:diamond", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/mine_diamond"}},
                {"id": "nether", "name": "勇往直下", "description": "做下界传送门（黑曜石 10 块 + 打火石）进下界",
                 "targets": [{"item": "minecraft:obsidian", "count": 10}, {"item": "minecraft:flint_and_steel", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/enter_the_nether"}},
                {"id": "enchant", "name": "附魔师", "description": "做附魔台并附魔一次",
                 "targets": [{"item": "minecraft:enchanting_table", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/enchant_item"}},
                {"id": "diamond_gear", "name": "钻石护体", "description": "穿上钻石盔甲",
                 "targets": [{"item": "minecraft:diamond_chestplate", "count": 1}],
                 "done_when": {"advancement": "minecraft:story/shiny_gear"}},
                {"id": "eye", "name": "隔墙有眼", "description": "打烈焰人、末影人，做末影之眼找要塞",
                 "targets": [{"item": "minecraft:ender_eye", "count": 12}],
                 "done_when": {"advancement": "minecraft:story/follow_ender_eye"}},
                {"id": "end", "name": "结束了？", "description": "激活末地传送门，进入末地",
                 "targets": [],
                 "done_when": {"advancement": "minecraft:story/enter_the_end"}},
                {"id": "dragon", "name": "解放末地", "description": "击败末影龙",
                 "targets": [],
                 "done_when": {"advancement": "minecraft:end/kill_dragon"}},
                {"id": "colony", "name": "建立殖民地", "description": "做补给营地（或补给船），放下市政厅开始自己的殖民地",
                 "optional": true, "requires_mod": "minecolonies",
                 "targets": [{"item": "minecolonies:supplycampdeployer", "count": 1}],
                 "done_when": {"any": [{"advancement": "minecolonies:minecolonies/place_townhall"},
                                       {"stat": "minecraft:used", "key": "minecolonies:blockhuttownhall", "min": 1}]}}
              ]
            }
            """;

    /** 错误信息最长多少字：Gson 有的异常会把整段输入塞进消息，原样转给服主/日志会刷屏。 */
    private static final int ERROR_MAX = 200;

    private final Path file;
    private Table table;
    private long loadedMtime = -1;
    private String error;
    private BooleanSupplier onMainThread = () -> false;
    private Executor offMain;
    /** 已经排了一次后台检查、还没执行完：主线程不再重复排。 */
    private boolean refreshQueued;

    public GoalTable(Path file) {
        this.file = file;
    }

    /** 文件不存在就生成默认表。返回 true = 新生成了。 */
    public synchronized boolean ensure() throws IOException {
        if (Files.exists(file)) return false;
        Files.createDirectories(file.getParent());
        Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
        return true;
    }

    /** 平台层接上：onMain 判断当前是不是服务端主线程，ex 是做文件 IO 的后台执行器（见类注释）。 */
    public synchronized void offMainThread(BooleanSupplier onMain, Executor ex) {
        this.onMainThread = onMain;
        this.offMain = ex;
    }

    /**
     * 当前表；从没成功读到过时返回 null。并发：GoalHooks 在主线程、工具在代理线程都会调用。
     * 主线程上（接了执行器且已经读到过表时）不做 IO，只返回已读到的表并排一次后台检查；其余情况在调用线程上检查文件（{@link #refresh}）。
     */
    public Table get() {
        Executor ex;
        synchronized (this) {
            if (table == null || offMain == null || !onMainThread.getAsBoolean()) ex = null;
            else if (refreshQueued) return table;
            else {
                refreshQueued = true;
                ex = offMain;
            }
        }
        if (ex == null) return refresh();
        try {
            ex.execute(this::refresh);
        } catch (RejectedExecutionException e) { // 关服时执行器已关：这次不检查
            synchronized (this) {
                refreshQueued = false;
            }
        }
        synchronized (this) {
            return table;
        }
    }

    /** 文件改过就重读（在调用线程上做 IO，锁外读、锁里换表）。返回当前表。 */
    public Table refresh() {
        long m;
        try {
            m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -2;
        } catch (IOException | RuntimeException e) {
            return failed(e, null);
        }
        synchronized (this) {
            if (m == loadedMtime) {
                refreshQueued = false;
                return table;
            }
        }
        Table parsed;
        try {
            String s = DEFAULT;
            if (m != -2) {
                s = Files.readString(file, StandardCharsets.UTF_8);
                if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
            }
            parsed = parse(s);
        } catch (IOException | RuntimeException e) {
            return failed(e, m); // 这一版文件读不了：记下修改时间，等服主再改时才重试
        }
        synchronized (this) {
            loadedMtime = m;
            table = parsed;
            error = null;
            refreshQueued = false;
            return table;
        }
    }

    private synchronized Table failed(Exception e, Long mtime) {
        if (mtime != null) loadedMtime = mtime;
        error = "goals.json 读取失败（保留上次的内容）：" + Text.brief(String.valueOf(e.getMessage()), ERROR_MAX);
        refreshQueued = false;
        return table;
    }

    public synchronized String error() {
        return error;
    }

    /** 当前表的非致命问题（字段类型不对、阶段缺 id…）；没有表时为空列表。 */
    public synchronized List<String> warnings() {
        return table == null ? List.of() : table.warnings();
    }

    public static Table parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject()) {
            // 不把整个文件内容塞进错误信息（旧实现靠 getAsJsonObject 抛异常，消息里是全文）
            throw new IllegalArgumentException("顶层应为对象 {…}，实际是" + kind(root));
        }
        JsonObject j = root.getAsJsonObject();
        Fields f = new Fields();
        JsonObject h = f.obj(j, "", "hints");
        JsonObject p = f.obj(j, "", "personal");
        Settings s = new Settings(f.bool(j, "", "enabled", true), clamp(f.intOf(j, "", "check_interval_seconds", 30), 5, 3600),
                f.bool(h, "hints.", "enabled", true), clamp(f.intOf(h, "hints.", "interval_minutes", 20), 1, 1440),
                clamp(f.intOf(h, "hints.", "min_online_minutes", 5), 0, 1440),
                clamp(f.intOf(h, "hints.", "repeat_unchanged_minutes", 60), 1, 10080), clamp(f.intOf(h, "hints.", "max_missing_shown", 3), 1, 10),
                clamp(f.intOf(p, "personal.", "max_per_player", 10), 0, 50), f.bool(p, "personal.", "announce_complete", false));
        List<Stage> stages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int i = 0;
        for (JsonElement e : f.arr(j, "", "stages")) {
            i++;
            if (!e.isJsonObject()) throw new IllegalArgumentException("stages 第 " + i + " 项不是对象");
            JsonObject o = e.getAsJsonObject();
            String id = f.str(o, "stages 第 " + i + " 项的 ", "id", null);
            if (id == null || id.isBlank()) {
                // 兼容：仍按位置给 id（老表没写 id 的照样能用），但位置 id 在中间插入阶段后会整体错位，所以提醒服主补上
                id = "stage" + i;
                f.warn("stages 第 " + i + " 项缺少 id，暂用位置 " + id + "；以后在它前面插入阶段，玩家的完成记录会错位，请补上固定的 id");
            }
            if (!ids.add(id)) throw new IllegalArgumentException("stages 里 id 重复：" + Text.brief(id, 60));
            String where = "阶段 " + id + " 的 ";
            List<Target> targets = new ArrayList<>();
            for (JsonElement te : f.arr(o, where, "targets")) {
                if (!te.isJsonObject()) {
                    f.warn(where + "targets 里有一项不是对象，已忽略");
                    continue;
                }
                JsonObject to = te.getAsJsonObject();
                String item = f.str(to, where + "targets 里的 ", "item", "");
                if (!item.isBlank()) targets.add(new Target(item.trim(), clamp(f.intOf(to, where + "targets 里的 ", "count", 1), 1, 100000)));
            }
            if (!o.has("done_when")) throw new IllegalArgumentException("阶段 " + id + " 缺少 done_when");
            Cond c = cond(o.get("done_when"), id, f);
            stages.add(new Stage(id, f.str(o, where, "name", id), f.str(o, where, "description", ""), List.copyOf(targets), c,
                    f.bool(o, where, "optional", false), f.str(o, where, "requires_mod", "").trim()));
        }
        return new Table(s, stages, f.warnings);
    }

    private static Cond cond(JsonElement e, String where, Fields f) {
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("阶段 " + where + " 的条件不是对象");
        JsonObject o = e.getAsJsonObject();
        if (o.has("all") || o.has("any")) {
            boolean all = o.has("all");
            JsonElement t = o.get(all ? "all" : "any");
            if (!t.isJsonArray() || t.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("阶段 " + where + " 的 all/any 必须是非空数组");
            List<Cond> terms = new ArrayList<>();
            for (JsonElement x : t.getAsJsonArray()) terms.add(cond(x, where, f));
            return all ? new Cond.All(List.copyOf(terms)) : new Cond.Any(List.copyOf(terms));
        }
        String w = "阶段 " + where + " 的条件里 ";
        if (o.has("item")) return new Cond.Item(f.str(o, w, "item", "").trim(), clamp(f.intOf(o, w, "count", 1), 1, 100000));
        if (o.has("advancement")) return new Cond.Adv(f.str(o, w, "advancement", "").trim());
        if (o.has("stat")) return new Cond.Stat(f.str(o, w, "stat", "").trim(), f.str(o, w, "key", "").trim(), Math.max(1, f.intOf(o, w, "min", 1)));
        throw new IllegalArgumentException("阶段 " + where + " 有不认识的条件：" + Text.brief(o.toString(), 120));
    }

    private static String kind(JsonElement e) {
        if (e == null || e.isJsonNull()) return "空的";
        if (e.isJsonArray()) return "数组 […]";
        return "单个值 " + Text.brief(e.toString(), 40);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 按类型读字段并收集警告。原因：服主手写 JSON 常把布尔/数字加上引号，旧实现静默取默认（"enabled":"false" 变成 true、
     * "count":"3" 变成 1）。边界：能看出意思的（"true"/"false"、纯整数字符串、数字写成的字符串字段）按意思理解并提醒去掉/加上引号；
     * 看不出意思的取默认值并说明；都不抛异常，一个类型错误不会让整份表读不了。
     */
    private static final class Fields {
        final List<String> warnings = new ArrayList<>();

        void warn(String w) {
            if (!warnings.contains(w)) warnings.add(w);
        }

        boolean bool(JsonObject o, String where, String k, boolean def) {
            JsonElement v = o.get(k);
            if (v == null || v.isJsonNull()) return def;
            if (Json.isBool(v)) return v.getAsBoolean();
            String s = Json.isString(v) ? v.getAsString().trim().toLowerCase(Locale.ROOT) : null;
            if ("true".equals(s) || "false".equals(s)) {
                warn(where + k + " 应为布尔值（true/false，不加引号），已按 " + s + " 理解");
                return s.equals("true");
            }
            warn(where + k + " 应为布尔值（true/false），实际是 " + brief(v) + "，按默认 " + def + " 处理");
            return def;
        }

        int intOf(JsonObject o, String where, String k, int def) {
            JsonElement v = o.get(k);
            if (v == null || v.isJsonNull()) return def;
            if (Json.isNumber(v)) {
                int n = Json.intOf(o, k, def);
                if (v.getAsDouble() != n) warn(where + k + " 应为整数，实际是 " + brief(v) + "，已按 " + n + " 处理");
                return n;
            }
            if (Json.isString(v)) {
                try {
                    int n = Integer.parseInt(v.getAsString().trim());
                    warn(where + k + " 应为整数（不加引号），已按 " + n + " 理解");
                    return n;
                } catch (NumberFormatException ignored) {
                    // 不是整数字符串：按类型错误处理
                }
            }
            warn(where + k + " 应为整数，实际是 " + brief(v) + "，按默认 " + def + " 处理");
            return def;
        }

        String str(JsonObject o, String where, String k, String def) {
            JsonElement v = o.get(k);
            if (v == null || v.isJsonNull()) return def;
            if (Json.isString(v)) return v.getAsString();
            if (v.isJsonPrimitive()) {
                // 兼容旧行为：数字/布尔当成文字用（"id": 3 → "3"，已有的完成记录不受影响）
                warn(where + k + " 应为字符串（加引号），已按 \"" + v.getAsString() + "\" 理解");
                return v.getAsString();
            }
            warn(where + k + " 应为字符串，实际是 " + brief(v) + "，按默认处理");
            return def;
        }

        JsonObject obj(JsonObject o, String where, String k) {
            JsonElement v = o.get(k);
            if (v != null && !v.isJsonNull() && !v.isJsonObject()) warn(where + k + " 应为对象 {…}，已忽略，里面的设置按默认");
            return Json.obj(o, k);
        }

        JsonArray arr(JsonObject o, String where, String k) {
            JsonElement v = o.get(k);
            if (v != null && !v.isJsonNull() && !v.isJsonArray()) warn(where + k + " 应为数组 […]，已忽略");
            return Json.arr(o, k);
        }

        private static String brief(JsonElement v) {
            return Text.brief(v.toString(), 40);
        }
    }
}
