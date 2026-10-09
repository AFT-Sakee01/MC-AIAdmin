package io.github.aftsakee.aiadmin.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Text {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 原版日志里的连接地址：名字[/1.2.3.4:5678]、(/1.2.3.4:5678)、/[2001:db8::1]:5678、/2001:db8:0:0:0:0:0:1:5678。 */
    private static final java.util.regex.Pattern LOG_IPV4 = java.util.regex.Pattern.compile("/\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?");
    private static final java.util.regex.Pattern LOG_IPV6 = java.util.regex.Pattern.compile("/\\[?(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:%[\\w.]+)?]?(?::\\d{1,5})?");
    /** JSON 里的 "ip": "…"（banned-ips.json、usercache 之类）。 */
    private static final java.util.regex.Pattern JSON_IP = java.util.regex.Pattern.compile("(?i)(\"(?:ip|address|remote_address)\"\\s*:\\s*\")[^\"]*(\")");

    private Text() {}

    public static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…（截断，共 " + s.length() + " 字）";
    }

    /** 一行内的简短摘要（日志用）。 */
    public static String brief(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 隐藏玩家 IP（服主没允许、或经远程接口且 remote.hide_player_ips 开着时，日志和文件输出都要过这一步）。
     * 边界：只认"斜杠开头的地址"（原版日志的写法）和 JSON 的 ip 字段，不碰版本号（1.21.1.235）这类普通数字串。
     */
    public static String redactIps(String s) {
        if (s == null || s.isEmpty()) return s;
        String r = LOG_IPV4.matcher(s).replaceAll("/[IP已隐藏]");
        r = LOG_IPV6.matcher(r).replaceAll("/[IP已隐藏]");
        return JSON_IP.matcher(r).replaceAll("$1[IP已隐藏]$2");
    }

    public static String now() {
        return LocalDateTime.now().format(LOCAL);
    }

    public static String time(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault()).format(LOCAL);
    }
}
