package io.github.aftsakee.aiadmin.util;

import java.util.Locale;
import java.util.Set;

/**
 * 玩家执行的指令写进"动态"（AI 能看到）之前的隐私处理。
 * <ul>
 *   <li>玩家之间私聊（msg/tell/w…）、队伍消息：只记"谁私聊了谁"，不记内容；</li>
 *   <li>登录/注册/改密码这类指令（离线服常装登录模组）：只记"执行了 /login"，参数（密码）不记；</li>
 *   <li>{@code execute … run <上面这些>} 按 run 之后的指令判断——否则套一层 execute 就把私聊正文或密码原样记进去；</li>
 *   <li>/ai 由 AgentService 自己记，这里返回 null（不记）。</li>
 * </ul>
 * 边界：只能按已知的根指令名判断，模组自定义的其它带密码指令需要加进 {@link #SENSITIVE}。
 */
public final class CommandPrivacy {
    /** 玩家之间私聊的指令（原版和常见别名）。 */
    public static final Set<String> WHISPER = Set.of("msg", "tell", "w", "whisper", "m", "pm", "dm", "r", "reply");
    public static final Set<String> TEAM_MSG = Set.of("teammsg", "tm");
    /** 参数里可能有密码的指令（AuthMe、NeoAuth、EasyAuth 等登录模组的常见写法）。 */
    public static final Set<String> SENSITIVE = Set.of("login", "l", "log", "register", "reg", "changepassword", "changepass", "changepw",
            "unregister", "unreg", "2fa", "totp", "auth", "authme", "password", "passwd", "setpassword", "logout");

    /** 记进动态的一条：kind = whisper / command，text = 要记的文字。 */
    public record Entry(String kind, String text) {}

    private CommandPrivacy() {}

    /** 返回要记的内容；null = 不记。raw 是玩家输入的原文（可带前导 /）。 */
    public static Entry describe(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        if (s.isEmpty()) return null;
        String[] w = s.split("\\s+");
        String root = root(w[0]);
        int from = 0;
        if (root.equals("execute")) {
            // 按最后一个 run 之后的指令判断（execute 可以嵌套多层）
            for (int i = w.length - 2; i >= 1; i--) {
                if (w[i].equalsIgnoreCase("run")) {
                    from = i + 1;
                    break;
                }
            }
        }
        String eff = root(w[from]);
        String via = from > 0 ? "（经 execute）" : "";
        if (WHISPER.contains(eff)) return new Entry("whisper", "私聊了 " + (w.length > from + 1 ? w[from + 1] : "?") + "（内容不记录）" + via);
        if (TEAM_MSG.contains(eff)) return new Entry("whisper", "发了队伍消息（内容不记录）" + via);
        if (SENSITIVE.contains(eff)) return new Entry("command", "/" + eff + "（参数不记录）" + via);
        if (from == 0 && eff.equals("ai")) return null;
        return new Entry("command", "/" + s);
    }

    private static String root(String token) {
        String r = token.toLowerCase(Locale.ROOT);
        int colon = r.indexOf(':'); // minecraft:msg 这类带命名空间的写法
        return colon >= 0 ? r.substring(colon + 1) : r;
    }
}
