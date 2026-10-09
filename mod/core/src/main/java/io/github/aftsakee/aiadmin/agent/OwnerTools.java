package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服主用文件扩展的能力（本地 AI 和远程接口都能用，改文件立即生效，不用改代码、不用重启）：
 * <ul>
 *   <li>宏：config/aiadmin/macros.json 里写好的"名字 + 参数 + 一串指令"，list_macros / run_macro；</li>
 *   <li>知识文档：config/aiadmin/guides/ 下的 .md / .txt，list_guides / read_guide 按需查阅（不进系统提示词，不占每次的上下文）。</li>
 * </ul>
 * 宏的边界：每条指令都经本地同一个 run_command 处理器执行（服主黑名单、恶作剧档位、每次运行的指令上限、审计全都照旧），
 * 宏只是把指令串起来，不提供任何额外权限；参数按类型严格校验后才代入，代入值里不可能出现换行或打断 JSON 字符串的字符；
 * 默认不允许在回应普通玩家聊天时使用（from_player_chat=true 的宏才行）。
 */
public final class OwnerTools {
    public static final String LIST_MACROS = "list_macros";
    public static final String RUN_MACRO = "run_macro";
    public static final String LIST_GUIDES = "list_guides";
    public static final String READ_GUIDE = "read_guide";
    static final int MAX_COMMANDS = 20;
    static final int PAGE = 6000;
    static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_][a-z0-9_]*)}");
    static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{3,16}");
    static final Pattern WORD = Pattern.compile("[A-Za-z0-9_:.\\-#/]{1,64}");

    private OwnerTools() {}

    record Param(String name, String type, String description, List<String> values, Double min, Double max, String def) {}

    record Macro(String name, String description, Map<String, Param> params, List<String> commands, boolean fromPlayerChat) {}

    record Loaded(Map<String, Macro> macros, List<String> errors) {}

    /** 每次调用都重读文件（文件很小），改完立即生效。单个宏写错只跳过它并报告，不影响其他宏。 */
    static Loaded loadMacros(Path file) {
        Map<String, Macro> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (!Files.exists(file)) return new Loaded(out, errors);
        JsonObject root;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            errors.add("macros.json 读不了或格式错误：" + e.getMessage());
            return new Loaded(out, errors);
        }
        JsonObject all = root.has("macros") && root.get("macros").isJsonObject() ? root.getAsJsonObject("macros") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : all.entrySet()) {
            try {
                out.put(e.getKey(), parseMacro(e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                errors.add(e.getKey() + "：" + ex.getMessage());
            }
        }
        return new Loaded(out, errors);
    }

    private static Macro parseMacro(String name, JsonElement el) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("宏名只能用小写字母、数字、下划线");
        if (!el.isJsonObject()) throw new IllegalArgumentException("要是一个对象");
        JsonObject o = el.getAsJsonObject();
        Map<String, Param> params = new LinkedHashMap<>();
        if (o.has("params") && o.get("params").isJsonObject()) {
            for (Map.Entry<String, JsonElement> p : o.getAsJsonObject("params").entrySet()) {
                if (!p.getKey().matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("参数名只能用小写字母、数字、下划线：" + p.getKey());
                JsonObject po = p.getValue().isJsonObject() ? p.getValue().getAsJsonObject() : new JsonObject();
                String type = str(po, "type", "word");
                if (!List.of("player", "int", "number", "word", "choice", "text").contains(type)) {
                    throw new IllegalArgumentException("参数 " + p.getKey() + " 的 type 只能是 player / int / number / word / choice / text");
                }
                List<String> values = new ArrayList<>();
                if (po.has("values") && po.get("values").isJsonArray()) for (JsonElement v : po.getAsJsonArray("values")) values.add(v.getAsString());
                if (type.equals("choice") && values.isEmpty()) throw new IllegalArgumentException("choice 参数 " + p.getKey() + " 要写 values");
                Double min = po.has("min") ? po.get("min").getAsDouble() : null;
                Double max = po.has("max") ? po.get("max").getAsDouble() : null;
                String def = po.has("default") && !po.get("default").isJsonNull() ? po.get("default").getAsString() : null;
                params.put(p.getKey(), new Param(p.getKey(), type, str(po, "description", ""), values, min, max, def));
            }
        }
        List<String> commands = new ArrayList<>();
        if (o.has("commands") && o.get("commands").isJsonArray()) for (JsonElement c : o.getAsJsonArray("commands")) commands.add(c.getAsString().trim());
        commands.removeIf(String::isEmpty);
        if (commands.isEmpty()) throw new IllegalArgumentException("commands 不能为空");
        if (commands.size() > MAX_COMMANDS) throw new IllegalArgumentException("最多 " + MAX_COMMANDS + " 条指令");
        for (String c : commands) {
            if (c.contains("\n")) throw new IllegalArgumentException("一条指令里不能有换行");
            Matcher m = PLACEHOLDER.matcher(c);
            while (m.find()) if (!params.containsKey(m.group(1))) throw new IllegalArgumentException("指令里用了没声明的参数 ${" + m.group(1) + "}");
        }
        return new Macro(name, str(o, "description", ""), params, commands, o.has("from_player_chat") && o.get("from_player_chat").getAsBoolean());
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    /** 按类型校验一个参数值，返回可以代入指令的文字；不合格抛 IllegalArgumentException。 */
    static String checkValue(Param p, String raw) {
        String v = raw == null ? "" : raw.trim();
        switch (p.type()) {
            case "player" -> {
                if (!PLAYER.matcher(v).matches()) throw new IllegalArgumentException(p.name() + " 要是玩家名（3~16 位字母数字下划线）");
            }
            case "int", "number" -> {
                double d;
                try {
                    d = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(p.name() + " 要是数字");
                }
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException(p.name() + " 要是数字");
                if (p.type().equals("int") && d != Math.rint(d)) throw new IllegalArgumentException(p.name() + " 要是整数");
                if (p.min() != null && d < p.min()) throw new IllegalArgumentException(p.name() + " 不能小于 " + fmt(p.min()));
                if (p.max() != null && d > p.max()) throw new IllegalArgumentException(p.name() + " 不能大于 " + fmt(p.max()));
                v = p.type().equals("int") ? String.valueOf((long) d) : fmt(d);
            }
            case "choice" -> {
                if (!p.values().contains(v)) throw new IllegalArgumentException(p.name() + " 只能是 " + String.join(" / ", p.values()));
            }
            case "text" -> {
                // 文字只允许出现在 JSON 字符串、单引号 SNBT 字符串（1.21 物品组件常用 custom_name='…'）或 say 里：
                // 去掉换行、控制字符和颜色符号，转义 \ 和 "，ASCII 单引号换成全角 ’（单引号 SNBT 里 \' 之外没有安全写法，
                // 而 \' 放进 JSON 字符串又是非法转义），最长 200 字。否则填 x',enchantments={…},a=' 就能跳出模板
                v = v.replaceAll("[\\p{Cntrl}§]", " ");
                if (v.length() > 200) throw new IllegalArgumentException(p.name() + " 最多 200 字");
                v = v.replace("\\", "\\\\").replace("\"", "\\\"").replace('\'', '’');
            }
            default -> {
                if (!WORD.matcher(v).matches()) throw new IllegalArgumentException(p.name() + " 只能是字母、数字和 _ : . - # /（不超过 64 字符）");
            }
        }
        return v;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 把参数代入指令；缺参数（且没有默认值）或类型不对抛 IllegalArgumentException。 */
    static List<String> expand(Macro m, JsonObject given) {
        Map<String, String> vals = new LinkedHashMap<>();
        for (Param p : m.params().values()) {
            String raw = given.has(p.name()) && !given.get(p.name()).isJsonNull() ? given.get(p.name()).getAsString() : p.def();
            if (raw == null) throw new IllegalArgumentException("缺少参数 " + p.name());
            vals.put(p.name(), checkValue(p, raw));
        }
        for (String k : given.keySet()) if (!m.params().containsKey(k)) throw new IllegalArgumentException("这个宏没有参数 " + k);
        List<String> out = new ArrayList<>();
        for (String c : m.commands()) {
            Matcher x = PLACEHOLDER.matcher(c);
            StringBuilder sb = new StringBuilder();
            while (x.find()) x.appendReplacement(sb, Matcher.quoteReplacement(vals.get(x.group(1))));
            x.appendTail(sb);
            out.add(sb.toString());
        }
        return out;
    }

    /** 宏的参数：对象原样用；也接受 JSON 字符串（有的模型/客户端会序列化）。 */
    static JsonObject paramsOf(JsonObject a) {
        JsonElement p = a.get("params");
        if (p == null || p.isJsonNull()) return new JsonObject();
        if (p.isJsonObject()) return p.getAsJsonObject();
        if (p.isJsonPrimitive()) {
            String t = p.getAsString().trim();
            if (t.isEmpty()) return new JsonObject();
            JsonElement e = JsonParser.parseString(t);
            if (e.isJsonObject()) return e.getAsJsonObject();
        }
        throw new IllegalArgumentException("params 要是 JSON 对象，例如 {\"player\":\"Steve\"}");
    }

    public static void register(Tools tools, Supplier<Path> configDir, JsonlLog log) {
        tools.add(Tools.schema(LIST_MACROS,
                        "列出服主写好的宏（config/aiadmin/macros.json）：每个宏是一串固定指令，带参数说明。能用宏完成的事优先用宏（服主事先审过）。")
                        .build(),
                (a, ctx) -> listMacros(configDir.get().resolve("macros.json")));
        tools.add(Tools.schema(RUN_MACRO,
                        "执行一个服主写好的宏：name = 宏名，params = 参数对象（例如 {\"player\":\"Steve\"}）。每条指令照样经过服主黑名单和每次运行的指令上限，"
                                + "某条被拒绝或失败就停下并报告。参数格式先用 list_macros 查。")
                        .str("name", "宏名", true)
                        .str("params", "参数，JSON 对象，例如 {\"player\":\"Steve\"}；没有参数就不填", false).build(),
                (a, ctx) -> runMacro(tools, configDir.get().resolve("macros.json"), a, ctx, log));
        tools.add(Tools.schema(LIST_GUIDES,
                        "列出服主写的知识文档（config/aiadmin/guides/ 下的 .md / .txt：服规细则、活动安排、模组玩法等），带标题和大小。回答这类问题前先查。")
                        .build(),
                (a, ctx) -> listGuides(configDir.get().resolve("guides")));
        tools.add(Tools.schema(READ_GUIDE,
                        "读一篇知识文档（名字用 list_guides 查，可以不带扩展名）。长文档分页，每页约 " + PAGE + " 字，page 从 1 开始。")
                        .str("name", "文档名", true)
                        .integer("page", "第几页，默认 1", false).build(),
                (a, ctx) -> readGuide(configDir.get().resolve("guides"), Tools.optStr(a, "name", ""), Tools.optInt(a, "page", 1, 1, 10000)));
    }

    private static String listMacros(Path file) {
        Loaded l = loadMacros(file);
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Macro m : l.macros().values()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", m.name());
            x.addProperty("description", m.description());
            JsonObject ps = new JsonObject();
            for (Param p : m.params().values()) {
                String t = p.type() + (p.type().equals("choice") ? "（" + String.join("/", p.values()) + "）" : "")
                        + (p.min() != null || p.max() != null ? "（" + (p.min() == null ? "" : fmt(p.min())) + "~" + (p.max() == null ? "" : fmt(p.max())) + "）" : "")
                        + (p.def() != null ? "，默认 " + p.def() : "") + (p.description().isEmpty() ? "" : "：" + p.description());
                ps.addProperty(p.name(), t);
            }
            x.add("params", ps);
            x.addProperty("commands", m.commands().size());
            if (m.fromPlayerChat()) x.addProperty("from_player_chat", true);
            arr.add(x);
        }
        o.add("macros", arr);
        if (!l.errors().isEmpty()) {
            JsonArray errs = new JsonArray();
            for (String e : l.errors()) errs.add(e);
            o.add("errors", errs);
        }
        if (arr.isEmpty() && l.errors().isEmpty()) o.addProperty("note", "服主还没有写宏（config/aiadmin/macros.json）");
        return o.toString();
    }

    private static String runMacro(Tools tools, Path file, JsonObject a, RunContext ctx, JsonlLog log) throws Exception {
        String name = Tools.optStr(a, "name", "").trim();
        Loaded l = loadMacros(file);
        Macro m = l.macros().get(name);
        if (m == null) {
            for (String e : l.errors()) if (e.startsWith(name + "：")) return "这个宏写错了，暂时不能用：" + e;
            return "没有这个宏：" + name + "（用 list_macros 查）";
        }
        if (ctx != null && ctx.trigger != null && ctx.trigger.kind() == Trigger.Kind.CHAT && !m.fromPlayerChat()) {
            return "这个宏只给服主/管理用，回应玩家聊天时不能执行（服主可以在 macros.json 里给它加 \"from_player_chat\": true）";
        }
        List<String> cmds;
        try {
            cmds = expand(m, paramsOf(a));
        } catch (RuntimeException e) {
            return "参数不对：" + e.getMessage();
        }
        Tools.Tool run = tools.get(ActionTools.RUN_COMMAND);
        if (run == null) return "执行指令的工具不可用";
        log.info("agent", "tool.macro", "执行宏：" + name + "（" + cmds.size() + " 条）", ctx == null ? null : ctx.cid, JsonlLog.data("macro", name));
        if (ctx != null) ctx.action("宏 " + name);
        JsonArray results = new JsonArray();
        boolean ok = true;
        for (String c : cmds) {
            JsonObject args = new JsonObject();
            args.addProperty("command", c);
            String r = run.handler().call(args, ctx);
            JsonObject x = new JsonObject();
            x.addProperty("command", c);
            JsonObject res;
            try {
                res = JsonParser.parseString(r).getAsJsonObject();
            } catch (RuntimeException e) {
                res = new JsonObject();
                res.addProperty("allowed", false);
                res.addProperty("reason", Text.brief(r, 200));
            }
            x.add("result", res);
            results.add(x);
            boolean allowed = res.has("allowed") && res.get("allowed").getAsBoolean();
            boolean success = !res.has("success") || res.get("success").getAsBoolean();
            if (!allowed || !success) {
                ok = false;
                break;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("macro", name);
        o.addProperty("completed", ok);
        o.addProperty("ran", results.size() + "/" + cmds.size());
        o.add("results", results);
        if (!ok) o.addProperty("note", "有一条被拒绝或失败，后面的没有执行");
        return o.toString();
    }

    /** guides 目录下的文档（只认这一层的 .md / .txt，按名字排序）。 */
    private static List<Path> guideFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return (n.endsWith(".md") || n.endsWith(".txt")) && !n.startsWith(".");
                    })
                    .sorted().toList();
        }
    }

    private static String listGuides(Path dir) throws IOException {
        List<Path> files = guideFiles(dir);
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Path p : files) {
            JsonObject x = new JsonObject();
            x.addProperty("name", p.getFileName().toString());
            String title = "";
            try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
                title = lines.map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("").replaceFirst("^#+\\s*", "");
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // 读不了的文件只列名字
            }
            x.addProperty("title", Text.brief(title, 80));
            x.addProperty("chars", Files.size(p));
            arr.add(x);
        }
        o.add("guides", arr);
        if (arr.isEmpty()) o.addProperty("note", "还没有知识文档（服主把 .md / .txt 放进 config/aiadmin/guides/ 即可，不用重启）");
        return o.toString();
    }

    private static String readGuide(Path dir, String name, int page) throws IOException {
        String want = name.trim();
        // 只在目录列表里按名字匹配，不拼路径：名字里写 ../ 之类也到不了别处
        Path hit = null;
        for (Path p : guideFiles(dir)) {
            String n = p.getFileName().toString();
            String stem = n.substring(0, n.lastIndexOf('.'));
            if (n.equals(want) || stem.equals(want)) hit = p;
        }
        if (hit == null) return "没有这篇文档：" + want + "（用 list_guides 查）";
        String text = Files.readString(hit, StandardCharsets.UTF_8);
        int pages = Math.max(1, (text.length() + PAGE - 1) / PAGE);
        if (page > pages) return "这篇只有 " + pages + " 页";
        String body = text.substring((page - 1) * PAGE, Math.min(text.length(), page * PAGE));
        return "【" + hit.getFileName() + "　第 " + page + "/" + pages + " 页】\n" + body;
    }
}
