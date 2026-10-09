package io.github.aftsakee.aiadmin.policy;

import io.github.aftsakee.aiadmin.config.Rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 代理执行后台指令前的黑名单检查。
 * <p>安全边界：代理以控制台权限执行指令，这里是唯一的拦截点，所以必须把"包在别的指令里的指令"也查到：
 * <ul>
 *   <li>根指令去掉前导 / 和 minecraft: 命名空间、转小写后比对 deny_roots；</li>
 *   <li>execute / return 里每一个独立的 {@code run} 记号之后的部分都当作一条新指令检查（玩家名恰好叫 run 时宁可误拦）；</li>
 *   <li>deny_patterns 对每一层规范化后的整条指令做 find；</li>
 *   <li>allow_only_roots 非空时，每一层的根指令都必须在白名单里；</li>
 *   <li>几条代码层硬限制（服主改黑名单也一样生效，见 {@link #hardDeny}）。</li>
 * </ul>
 * 复杂度约束：每层只检查一次（"run 之后的所有后缀"一次列出来，见 {@link #layers}），不递归展开——
 * 以前每层都对后面每个 run 递归，耗时随嵌套层数指数增长，十几层 execute run 就能让单线程的代理卡住几秒到几分钟。
 * 同时限制指令长度和 run 的个数。
 * 不能防的：数据包函数、命令方块里预先写好的指令（function 以 function-permission-level 执行，通常够不到 stop/op）。
 */
public final class CommandPolicy {
    /** 一条指令里最多几个 run（嵌套层数）。 */
    private static final int MAX_DEPTH = 16;
    /** 指令最长字符数：控制台指令本身几乎不限长，但代理没有理由发超长指令，长度也直接决定检查耗时。 */
    public static final int MAX_LENGTH = 8192;
    private static final Pattern WS = Pattern.compile("\\s+");
    /** 选择器：@a @e @p @r @s @n，可带 [筛选]。 */
    private static final Pattern SELECTOR = Pattern.compile("^@([aeprsn])(\\[(.*)])?$");

    private final Set<String> denyRoots = new HashSet<>();
    private final Set<String> allowOnly = new HashSet<>();
    private final List<Pattern> denyPatterns = new ArrayList<>();

    public record Verdict(boolean allowed, String reason) {
        static Verdict ok() {
            return new Verdict(true, "");
        }

        static Verdict deny(String r) {
            return new Verdict(false, r);
        }
    }

    public CommandPolicy(Rules.Permissions c) {
        for (String r : c.denyRoots) denyRoots.add(stripNs(r.trim().toLowerCase(Locale.ROOT)));
        for (String r : c.allowOnlyRoots) allowOnly.add(stripNs(r.trim().toLowerCase(Locale.ROOT)));
        for (String p : c.denyPatterns) denyPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
    }

    /** 规范化：去首尾空白和前导 /，压缩连续空白。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        return WS.matcher(s).replaceAll(" ");
    }

    public Verdict check(String raw) {
        String cmd = normalize(raw);
        if (cmd.isEmpty()) return Verdict.deny("空指令");
        if (cmd.length() > MAX_LENGTH) return Verdict.deny("指令太长（最多 " + MAX_LENGTH + " 个字符）");
        List<String> layers = layers(cmd);
        if (layers.size() > MAX_DEPTH + 1) return Verdict.deny("嵌套层数过多");
        for (String layer : layers) {
            Verdict v = checkLayer(layer);
            if (!v.allowed()) return v;
        }
        return Verdict.ok();
    }

    /**
     * 一条（已规范化的）指令里要分别检查的各层：整条指令本身；根指令是 execute / return 时，再加上每个独立 run 记号之后的部分。
     * 例：{@code execute as @a run execute at @s run say hi} → [整条, "execute at @s run say hi", "say hi"]。
     * 只在最外层是 execute/return 时展开（{@code say run stop} 里的 run 只是文本）；外层是 execute 时，
     * 后面每个 run 之后的部分都列出——包括写在文本里的 run，宁可误拦。
     */
    public static List<String> layers(String cmd) {
        List<String> out = new ArrayList<>();
        out.add(cmd);
        String[] toks = cmd.split(" ");
        String root = stripNs(toks[0].toLowerCase(Locale.ROOT));
        if (!root.equals("execute") && !root.equals("return")) return out;
        for (int i = 1; i + 1 < toks.length; i++) {
            if (toks[i].equalsIgnoreCase("run")) out.add(String.join(" ", java.util.Arrays.copyOfRange(toks, i + 1, toks.length)));
        }
        return out;
    }

    /** 根指令（去命名空间、小写）。 */
    public static String root(String layer) {
        int sp = layer.indexOf(' ');
        return stripNs((sp < 0 ? layer : layer.substring(0, sp)).toLowerCase(Locale.ROOT));
    }

    private Verdict checkLayer(String cmd) {
        String[] toks = cmd.split(" ");
        String root = stripNs(toks[0].toLowerCase(Locale.ROOT));
        if (!allowOnly.isEmpty() && !allowOnly.contains(root)) return Verdict.deny("不在允许的指令列表里：" + root);
        if (denyRoots.contains(root)) return Verdict.deny("服主禁止代理使用指令：" + root);
        // 代码层硬限制，不依赖黑名单（服主改了黑名单也一样）：代理不能批准/吊销远程管理的 OAuth 授权——
        // 否则聊天里的提示注入可以让机器人替攻击者执行 /aibot remote approve
        if (root.equals("aibot") && toks.length > 1 && toks[1].equalsIgnoreCase("remote")) return Verdict.deny("代理永远不能使用 aibot remote");
        String rooted = root + (toks.length > 1 ? cmd.substring(toks[0].length()) : "");
        // 文本组件/SNBT 里可以用 \\u0045 这类转义、给键加引号来躲开正则（click\\u0045vent、"Command":）：
        // 同时拿"解码转义、去掉引号和反斜杠"后的版本再匹配一遍
        String decoded = decode(rooted);
        // 代码层硬限制：任何地方（点击事件、书、告示牌的文本）出现 aibot remote 都拒绝——防止诱骗服主点击批准 OAuth
        if (decoded.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").contains("aibot remote")) return Verdict.deny("代理永远不能使用 aibot remote");
        for (Pattern p : denyPatterns) {
            if (p.matcher(rooted).find() || p.matcher(decoded).find()) return Verdict.deny("命中禁止规则：" + p.pattern());
        }
        String hard = hardDeny(root, toks, decoded);
        return hard == null ? Verdict.ok() : Verdict.deny(hard);
    }

    /** 改命令方块（或命令方块矿车）里的指令/自动执行开关：以 2 级权限执行、完全绕过本黑名单。 */
    private static final Pattern CMD_BLOCK_KEY = Pattern.compile("(?i)(^|[^A-Za-z0-9_])(Command|auto)([^A-Za-z0-9_]|$)");

    /**
     * 代码层硬限制（不依赖 rules.json，服主改了黑名单也一样）。默认 deny_patterns 只按字面拦，
     * {@code kill @e[]}、{@code kill @e[distance=0..]}、{@code data modify block … Command set value …} 都能换写法绕过，
     * 而这几类后果不可逆或等于提权，所以按语义在这里再拦一次：
     * <ul>
     *   <li>kill @e 必须用 type= 排除玩家（type=某种非玩家实体，或 type=!player）；kill @a / @r 必须用 name= 指定玩家；</li>
     *   <li>data modify / merge 不能碰命令方块的 Command 和 auto（方块和实体都算：命令方块矿车也有 Command）。</li>
     * </ul>
     * 返回 null = 放行。
     */
    static String hardDeny(String root, String[] toks, String decoded) {
        if (root.equals("kill") && toks.length > 1) {
            String target = String.join("", java.util.Arrays.copyOfRange(toks, 1, toks.length)); // 选择器里的空格不影响判断
            java.util.regex.Matcher m = SELECTOR.matcher(target);
            if (m.matches()) {
                String kind = m.group(1);
                String filter = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
                if (kind.equals("e") && !excludesPlayers(filter)) return "kill @e 必须用 type= 指定非玩家实体（例如 @e[type=minecraft:item]），不能连玩家一起杀";
                if ((kind.equals("a") || kind.equals("r")) && !hasPositive(filter, "name")) return "kill @" + kind + " 必须用 name= 指定玩家，不能一次杀掉全部玩家";
            }
        }
        if (root.equals("data") && toks.length > 2) {
            String sub = toks[1].toLowerCase(Locale.ROOT);
            String what = toks[2].toLowerCase(Locale.ROOT);
            // decoded 以根指令开头（"data modify block …"），跳过根指令再找键名，避免把子命令本身算进去
            String rest = decoded.substring(Math.min(decoded.length(), decoded.indexOf(' ') + 1));
            if ((sub.equals("modify") || sub.equals("merge")) && (what.equals("block") || what.equals("entity")) && CMD_BLOCK_KEY.matcher(rest).find()) {
                return "不能用 data 修改命令方块的 Command / auto（等于绕过指令黑名单）";
            }
        }
        return null;
    }

    /** 选择器筛选里是否排除了玩家：type=某种非玩家实体（不带 !、不是标签），或 type=!player。 */
    private static boolean excludesPlayers(String filter) {
        for (String part : filter.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0 || !part.substring(0, eq).trim().equals("type")) continue;
            String v = part.substring(eq + 1).trim();
            boolean neg = v.startsWith("!");
            String t = stripNs(neg ? v.substring(1).trim() : v);
            if (neg ? t.equals("player") : !t.isEmpty() && !t.equals("player") && !t.startsWith("#")) return true;
        }
        return false;
    }

    /** 筛选里有没有某个不取反、非空的参数（name=Steve 算，name=!Steve、name= 不算）。 */
    private static boolean hasPositive(String filter, String key) {
        for (String part : filter.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0 || !part.substring(0, eq).trim().equals(key)) continue;
            String v = part.substring(eq + 1).trim();
            if (!v.isEmpty() && !v.startsWith("!")) return true;
        }
        return false;
    }

    /** 解码 \\uXXXX（可多层反斜杠）、去掉引号和剩余的反斜杠，只用于匹配。 */
    static String decode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                int j = i;
                while (j < s.length() && s.charAt(j) == '\\') j++;
                if (j + 4 < s.length() + 0 && j < s.length() && (s.charAt(j) == 'u' || s.charAt(j) == 'U') && j + 4 < s.length()
                        && s.substring(j + 1, j + 5).matches("[0-9a-fA-F]{4}")) {
                    out.append((char) Integer.parseInt(s.substring(j + 1, j + 5), 16));
                    i = j + 4;
                    continue;
                }
                i = j - 1;
                continue;
            }
            if (ch == '"' || ch == '\'') continue;
            out.append(ch);
        }
        return out.toString();
    }

    static String stripNs(String root) {
        return root.startsWith("minecraft:") ? root.substring("minecraft:".length()) : root;
    }
}
