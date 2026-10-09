package io.github.aftsakee.aiadmin.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * read_file / list_files 的安全边界：
 * <ul>
 *   <li>路径必须在服务器目录内（规范化后比较，挡住 ../ 和绝对路径）；</li>
 *   <li>本模组设置目录 config/aiadmin/ 永远禁止（里面有 API key），只放一个密钥的文件（*.secret、.env，如 Velocity 的 forwarding.secret）
 *       也永远禁止——里面没有键名，按内容脱敏认不出来；再加上规则里的 file_deny glob；</li>
 *   <li>读出的文本里 password/secret/token/api_key/authorization 之类的配置值替换成 [已隐藏]：整行"键=值"、行内任意位置的
 *       "键": "值" / 键=值、Bearer 令牌、连接串里的口令（scheme://用户:口令@主机）。</li>
 * </ul>
 * 符号链接、联接点、短文件名：文件存在时用 toRealPath 解析后再检查一次越界和黑名单（见 {@link #checked}）；glob 不分大小写（Windows 上文件名不分大小写）。
 */
public final class FileGuard {
    public static final String ALWAYS_DENY = "config/aiadmin/**";
    /** 只有一个密钥、没有键名的文件：内容脱敏认不出来，直接禁止。 */
    public static final java.util.List<String> ALWAYS_DENY_FILES = java.util.List.of("**/*.secret", "**/.env", "**/.env.*");
    private static final Pattern SECRET_LINE = Pattern.compile(
            "(?i)^(\\s*\"?[\\w.\\-]*(?:password|passwd|secret|token(?!s\\b)|api[_-]?key|access[_-]?key|private[_-]?key|authorization)[\\w.\\-]*\"?\\s*[=:]\\s*)(.+)$");
    /** 行内任意位置的"键": "值" 或 键=值（单行 JSON、export API_KEY=…、-Dx.password=…）。token 不含复数（max_tokens 不算）。 */
    private static final Pattern SECRET_INLINE = Pattern.compile(
            "(?i)(\"?[\\w.\\-]*(?:password|passwd|secret|token(?!s\\b)|api[_-]?key|access[_-]?key|private[_-]?key|authorization|cookie)[\\w.\\-]*\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^/\\s:@]+:)[^@\\s/]+(@)");

    private FileGuard() {}

    /** 解析相对服务器目录的路径；越界返回 null。 */
    public static Path resolve(Path root, String rel) {
        Path r = root.toAbsolutePath().normalize();
        Path p = r.resolve(rel == null || rel.isBlank() ? "." : rel.replace('\\', '/')).normalize();
        return p.startsWith(r) ? p : null;
    }

    /** 相对路径（用 / 分隔）。 */
    public static String rel(Path root, Path p) {
        String s = root.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    /**
     * 代理要访问的路径：校验通过返回规范化后的 Path，否则抛 IllegalArgumentException。
     * <p>Windows 安全边界：文件系统打开文件时会忽略路径段末尾的点和空格（"config/aiadmin./x" 实际打开 "config/aiadmin/x"），
     * 冒号可以访问 NTFS 备用数据流，8.3 短名（AIADMI~1）和符号链接/联接点也能换个名字指向同一目录。
     * 所以：先拒绝含这些写法的路径；文件存在时再用 toRealPath()（解析短名、链接、大小写）重新做一次越界和黑名单检查。
     */
    public static Path checked(Path root, String rel, List<String> globs) {
        String r = rel == null ? "" : rel.replace('\\', '/');
        for (String seg : r.split("/")) {
            if (seg.equals(".") || seg.equals("..") || seg.isEmpty()) continue;
            if (seg.endsWith(".") || seg.endsWith(" ") || seg.contains(":") || seg.contains("~")) {
                throw new IllegalArgumentException("路径写法不允许（末尾的点/空格、冒号、短文件名）：" + rel);
            }
        }
        Path p = resolve(root, r);
        if (p == null) throw new IllegalArgumentException("只能访问服务器目录以内");
        String shown = rel(root, p);
        if (denied(shown, globs)) throw new IllegalArgumentException("服主禁止访问：" + shown);
        if (java.nio.file.Files.exists(p)) {
            try {
                Path realRoot = root.toRealPath();
                Path real = p.toRealPath();
                if (!real.startsWith(realRoot)) throw new IllegalArgumentException("只能访问服务器目录以内（链接指向了外面）");
                String rr = realRoot.relativize(real).toString().replace('\\', '/');
                if (!rr.isEmpty() && denied(rr, globs)) throw new IllegalArgumentException("服主禁止访问：" + shown);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("无法解析路径：" + shown);
            }
        }
        return p;
    }

    /**
     * 有界地按行读取：单行最多 maxLineChars 字（多的丢弃并标记），总共最多读 maxTotalBytes 字节（防止超长单行或高压缩比 .gz 撑爆内存）。
     * 回调返回 false 时提前停止。返回是否因字节上限被截断。
     */
    public static boolean readLines(java.io.InputStream in, int maxLineChars, long maxTotalBytes,
                                    java.util.function.Predicate<String> onLine) throws java.io.IOException {
        Bounded bounded = new Bounded(in, maxTotalBytes);
        java.io.Reader rd = new java.io.BufferedReader(new java.io.InputStreamReader(bounded, java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        boolean over = false;
        int c;
        while ((c = rd.read()) != -1) {
            if (c == '\n') {
                if (!onLine.test(finish(sb, over))) return false;
                sb.setLength(0);
                over = false;
            } else if (c != '\r') {
                if (sb.length() < maxLineChars) sb.append((char) c);
                else over = true;
            }
        }
        if (sb.length() > 0 || over) onLine.test(finish(sb, over));
        return bounded.hitLimit;
    }

    /** 最多放行 limit 字节；到上限后如果底层还有数据，记下 hitLimit。 */
    private static final class Bounded extends java.io.FilterInputStream {
        long left;
        boolean hitLimit;

        Bounded(java.io.InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws java.io.IOException {
            if (left <= 0) return limitReached();
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            if (left <= 0) return limitReached();
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        private int limitReached() throws java.io.IOException {
            if (!hitLimit && super.read() >= 0) hitLimit = true;
            return -1;
        }
    }

    private static String finish(StringBuilder sb, boolean over) {
        return over ? sb + "…（这一行太长，已截断）" : sb.toString();
    }

    public static boolean denied(String relPath, List<String> globs) {
        String p = relPath.replace('\\', '/');
        if (matches(ALWAYS_DENY, p) || p.equalsIgnoreCase("config/aiadmin")) return true;
        for (String g : ALWAYS_DENY_FILES) if (matches(g, p)) return true;
        for (String g : globs) if (matches(g, p)) return true;
        return false;
    }

    /** glob：** 跨目录，* 和 ? 不跨 /；"**&#47;x" 也匹配根目录下的 x。 */
    public static boolean matches(String glob, String path) {
        return toRegex(glob).matcher(path).matches();
    }

    static Pattern toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        String g = glob.replace('\\', '/');
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                boolean slash = i + 2 < g.length() && g.charAt(i + 2) == '/';
                sb.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') sb.append("[^/]*");
            else if (c == '?') sb.append("[^/]");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 隐藏像密码/密钥的配置值，返回 [处理后的文本, 隐藏了几处]。 */
    public static List<Object> redact(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int n = 0;
        for (String l : lines) {
            Matcher m = SECRET_LINE.matcher(l);
            if (m.matches() && !m.group(2).isBlank()) {
                out.add(m.group(1) + "[已隐藏]");
                n++;
                continue;
            }
            String r = SECRET_INLINE.matcher(l).replaceAll(x -> Matcher.quoteReplacement(x.group(1) + (x.group(2).startsWith("\"") ? "\"[已隐藏]\"" : "[已隐藏]")));
            r = BEARER.matcher(r).replaceAll("$1[已隐藏]");
            r = URL_PASSWORD.matcher(r).replaceAll("$1[已隐藏]$2");
            if (!r.equals(l)) n++;
            out.add(r);
        }
        return List.of(out, n);
    }

    public static boolean looksBinary(byte[] head, int len) {
        for (int i = 0; i < len; i++) if (head[i] == 0) return true;
        return false;
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
