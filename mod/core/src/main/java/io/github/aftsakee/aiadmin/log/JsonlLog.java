package io.github.aftsakee.aiadmin.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import com.google.gson.JsonElement;

/**
 * 程序日志（全局规则"程序日志"）：&lt;游戏目录&gt;/logs/aiadmin/full.jsonl、error.jsonl、error-dedup-state.json。
 * <ul>
 *   <li>一行一个 JSON：schema_version、timestamp_utc/local、timezone、level、process、thread、module、event、message、correlation_id、data。</li>
 *   <li>写入前按 UTF-8 字节判断：加上这一行会超过 5 MiB 就先滚动（原子改名 + gzip 成 UTC 时间戳 .jsonl.gz，每类留 3 份）；
 *       单行超过 512 KiB 时截断 message、丢弃 data，所以单文件不会超过 6 MiB。滚动失败改写 *-fallback.jsonl，同样受 5 MiB 限制，满了就丢。</li>
 *   <li>error 按"模块 + 事件 + 规范化消息（数字归一）"指纹 10 分钟内只写首条，期间累计次数；同一窗口里每多 100 次、窗口结束
 *       （同一错误再出现时）、24 小时不活动被清理前、close() 时写 error_dedup_summary（count = 窗口内总次数）。
 *       fatal 和 {@link #unhandled}（未处理异常）每次都写、不参与排重。排重状态原子保存（新指纹立即存，重复的最多 5 秒存一次），
 *       最多 1000 项。</li>
 *   <li>写日志失败绝不抛出、不递归记录（游戏不能因为日志崩）；data 里有序列化不了的值时退回成字符串再写，不丢整行。</li>
 *   <li>脱敏：data 里（含嵌套的对象和列表）名字像 key/token/secret/password/cookie/authorization 的字段一律替换为 [redacted]，
 *       键名不分大小写、忽略 - 和 _（x-api-key、accessToken 都算）；message 和所有字符串值里的 Bearer 令牌、sk- / whsec_ 密钥也替换掉。</li>
 * </ul>
 * 所有写方法加同一把锁：代理线程、服务端主线程、日志旁路会同时写。
 */
public final class JsonlLog {
    public static final long ROTATE_BYTES = 5L << 20;
    public static final int MAX_LINE_BYTES = 512 << 10;
    public static final int KEEP = 3;
    public static final long DEDUP_MS = 10 * 60_000L;
    static final int DEDUP_SUMMARY_AT = 100;
    static final int DEDUP_MAX_ENTRIES = 1000;
    static final long DEDUP_IDLE_PURGE_MS = 24 * 3600_000L;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");
    /**
     * 键名（转小写、去掉 - 和 _ 之后）以这些词结尾才算密钥：apikey、accesstoken、xapikey、clientsecret…；
     * token 不带复数——input_tokens、max_tokens 这类计数字段不能误伤，token_usage 也不算。
     */
    private static final Pattern SECRET_KEY = Pattern.compile("(apikey|token|secret|password|passwd|cookie|authorization|bearer|privatekey|credential|credentials)");
    /** 字符串里的令牌/密钥（message 和 data 的值）：Bearer 令牌、OpenAI/Anthropic 风格的 sk- 密钥、webhook 的 whsec_ 密钥。 */
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}|\\b(sk-)[A-Za-z0-9_-]{12,}|\\b(whsec_)[A-Za-z0-9+/=]{12,}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Path dir;
    private final String process;
    private final BiConsumer<String, String> mirror; // (level, text) 同时写进 MC 日志，可为 null；info 不镜像
    private final Map<String, long[]> dedup = new LinkedHashMap<>(); // 指纹 -> {窗口开始, 最后一次, 次数}
    private final long rotateBytes;
    private volatile boolean broken;

    public JsonlLog(Path dir, BiConsumer<String, String> mirror) {
        this(dir, mirror, ROTATE_BYTES);
    }

    /** 测试用：可以把滚动阈值调小。 */
    public JsonlLog(Path dir, BiConsumer<String, String> mirror, long rotateBytes) {
        this.dir = dir;
        this.mirror = mirror;
        this.rotateBytes = rotateBytes;
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        this.process = (at > 0 ? pid.substring(0, at) : pid) + "/aiadmin";
        loadDedup();
    }

    public void info(String module, String event, String msg, String cid, Map<String, Object> data) {
        write("info", module, event, msg, cid, data);
    }

    public void warn(String module, String event, String msg, String cid, Map<String, Object> data) {
        write("warn", module, event, msg, cid, data);
    }

    public void error(String module, String event, String msg, String cid, Map<String, Object> data) {
        write("error", module, event, msg, cid, data);
    }

    public void error(String module, String event, String msg, String cid, Throwable t) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (t != null) {
            d.put("exception_type", t.getClass().getName());
            d.put("exception_message", String.valueOf(t.getMessage()));
            d.put("stack", firstFrames(t, 6));
        }
        write("error", module, event, msg, cid, d);
    }

    public void fatal(String module, String event, String msg, String cid, Map<String, Object> data) {
        write("fatal", module, event, msg, cid, data);
    }

    /** 未处理异常（全局规则：每次都写，不参与排重）。级别仍是 error，data 里标 unhandled=true。 */
    public void unhandled(String module, String event, String msg, String cid, Throwable t) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("unhandled", true);
        if (t != null) {
            d.put("exception_type", t.getClass().getName());
            d.put("exception_message", String.valueOf(t.getMessage()));
            d.put("stack", firstFrames(t, 8));
        }
        write("error", module, event, msg, cid, d, false);
    }

    /** 便捷构造 data：data("k1", v1, "k2", v2)。 */
    public static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public void write(String level, String module, String event, String msg, String cid, Map<String, Object> data) {
        write(level, module, event, msg, cid, data, true);
    }

    private synchronized void write(String level, String module, String event, String msg, String cid,
                                    Map<String, Object> data, boolean dedupable) {
        msg = redactText(msg);
        if (mirror != null && !level.equals("info")) {
            try {
                mirror.accept(level, "[" + module + "/" + event + "] " + msg);
            } catch (RuntimeException ignored) {
                // 镜像失败不影响 JSONL
            }
        }
        try {
            long now = System.currentTimeMillis();
            JsonObject o;
            try {
                o = line(level, module, event, msg, cid, data, now);
            } catch (RuntimeException e) {
                // data 里有 Gson 序列化不了的值（Path、循环引用…）：退回成字符串，不能让整行（可能是 fatal）丢掉
                o = line(level, module, event, msg, cid, data("data_unserializable", clip(String.valueOf(data), 4000)), now);
            }
            if (level.equals("error") && dedupable) {
                String fp = module + "|" + event + "|" + DIGITS.matcher(String.valueOf(msg)).replaceAll("#");
                long[] d = dedup.get(fp);
                if (d != null && now - d[0] < DEDUP_MS) {
                    // 窗口内：只累计。d = {窗口开始, 最后一次, 窗口内总次数, 上次汇总时的总次数}
                    d[1] = now;
                    d[2]++;
                    if (d[2] - d[3] >= DEDUP_SUMMARY_AT) { // 每多 100 次写一条汇总，窗口不重开（10 分钟内仍只写首条）
                        writeSummary(fp, d, now);
                        d[3] = d[2];
                    }
                    saveDedup(now, false);
                    append("full.jsonl", GSON.toJson(o)); // full 里照记，error 里排重
                    return;
                }
                if (d != null && d[2] > Math.max(1, d[3])) writeSummary(fp, d, now);
                dedup.put(fp, new long[] {now, now, 1, 0});
                saveDedup(now, true);
            }
            String s = GSON.toJson(o);
            append("full.jsonl", s);
            if (level.equals("error") || level.equals("fatal")) append("error.jsonl", s);
        } catch (RuntimeException | IOException e) {
            broken = true; // 不递归记录
        }
    }

    /** 正常退出时把排重窗口里累计的次数写成汇总。 */
    public synchronized void close() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, long[]> e : dedup.entrySet()) {
                if (e.getValue()[2] > Math.max(1, e.getValue()[3])) writeSummary(e.getKey(), e.getValue(), now);
            }
            dedup.clear();
            saveDedup(now, true);
        } catch (RuntimeException | IOException e) {
            broken = true;
        }
    }

    public boolean broken() {
        return broken;
    }

    private void writeSummary(String fp, long[] d, long now) throws IOException {
        String[] parts = fp.split("\\|", 3);
        JsonObject o = line("error", parts[0], "error_dedup_summary", "重复错误汇总：" + (parts.length > 2 ? parts[2] : fp), null,
                data("fingerprint_event", parts.length > 1 ? parts[1] : "", "count", d[2],
                        "first_utc", Instant.ofEpochMilli(d[0]).toString(), "last_utc", Instant.ofEpochMilli(d[1]).toString()), now);
        String s = GSON.toJson(o);
        append("full.jsonl", s);
        append("error.jsonl", s);
    }

    private JsonObject line(String level, String module, String event, String msg, String cid, Map<String, Object> data, long now) {
        JsonObject o = new JsonObject();
        OffsetDateTime t = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault());
        o.addProperty("schema_version", 1);
        o.addProperty("timestamp_utc", t.withOffsetSameInstant(ZoneOffset.UTC).format(UTC));
        o.addProperty("timestamp_local", t.toString());
        o.addProperty("timezone", ZoneId.systemDefault().getId());
        o.addProperty("level", level);
        o.addProperty("process", process);
        o.addProperty("thread", Thread.currentThread().getName());
        o.addProperty("module", module);
        o.addProperty("event", event);
        o.addProperty("message", msg);
        o.addProperty("correlation_id", cid);
        o.add("data", redactJson(GSON.toJsonTree(data == null ? Collections.emptyMap() : data)));
        if (GSON.toJson(o).getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            // 超长行：截断正文、丢掉 data，保证单文件硬上限
            o.addProperty("message", clip(String.valueOf(msg), 4000) + "…[truncated]");
            o.add("data", GSON.toJsonTree(data("truncated", true)));
        }
        return o;
    }

    /** 键名是否像密钥（见 SECRET_KEY）。 */
    static boolean secretKey(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        java.util.regex.Matcher m = SECRET_KEY.matcher(k);
        while (m.find()) if (m.end() == k.length()) return true;
        return false;
    }

    /** 字符串里的令牌/密钥换成 [redacted]（前缀保留，便于看出是哪种）。 */
    static String redactText(String s) {
        if (s == null || s.isEmpty()) return s;
        return SECRET_VALUE.matcher(s).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                (m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3)) + "[redacted]"));
    }

    /** 递归脱敏：对象按键名、字符串按内容；列表逐项。 */
    static JsonElement redactJson(JsonElement e) {
        if (e == null || e.isJsonNull()) return e;
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                out.add(x.getKey(), secretKey(x.getKey()) && !x.getValue().isJsonNull() ? new com.google.gson.JsonPrimitive("[redacted]") : redactJson(x.getValue()));
            }
            return out;
        }
        if (e.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(redactJson(x));
            return out;
        }
        if (e.getAsJsonPrimitive().isString()) return new com.google.gson.JsonPrimitive(redactText(e.getAsString()));
        return e;
    }

    /** 兼容旧调用：只做第一层（测试用）。 */
    static Map<String, Object> redact(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), secretKey(e.getKey()) ? "[redacted]" : e.getValue());
        return out;
    }

    private void append(String name, String line) throws IOException {
        Files.createDirectories(dir);
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Path p = dir.resolve(name);
        if (Files.exists(p) && Files.size(p) + b.length > rotateBytes) {
            try {
                rotate(p, name);
            } catch (IOException | RuntimeException e) {
                // 滚动失败（文件被占用等）：改写备用文件，同样不得超过上限
                Path alt = dir.resolve(name.replace(".jsonl", "-fallback.jsonl"));
                if (Files.exists(alt) && Files.size(alt) + b.length > rotateBytes) return;
                Files.write(alt, b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            }
        }
        Files.write(p, b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void rotate(Path p, String name) throws IOException {
        String stem = name.substring(0, name.length() - ".jsonl".length());
        String ts = OffsetDateTime.now(ZoneOffset.UTC).format(FILE_TS);
        Path moved = dir.resolve(stem + "-" + ts + ".jsonl");
        Files.move(p, moved, StandardCopyOption.ATOMIC_MOVE);
        // 压缩先写 .gz.tmp 再改名：压到一半失败不会留下占保留名额的半截 .gz；没压成的 -时间戳.jsonl 下次滚动时补压或清理
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, stem + "-2*.jsonl")) {
            for (Path x : ds) gzip(x);
        }
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, stem + "-2*.jsonl.gz")) {
            for (Path x : ds) old.add(x);
        }
        old.sort(Comparator.comparing(Path::toString)); // 文件名带 UTC 时间戳，字典序 = 时间序
        for (int i = 0; i < old.size() - KEEP; i++) Files.deleteIfExists(old.get(i));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, stem + "-2*.jsonl.gz.tmp")) {
            for (Path x : ds) Files.deleteIfExists(x);
        }
    }

    private static void gzip(Path plain) throws IOException {
        Path gz = plain.resolveSibling(plain.getFileName() + ".gz");
        Path tmp = plain.resolveSibling(plain.getFileName() + ".gz.tmp");
        try (InputStream in = Files.newInputStream(plain);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            in.transferTo(out);
        }
        Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(plain);
    }

    private void loadDedup() {
        try {
            Path p = dir.resolve("error-dedup-state.json");
            if (!Files.exists(p)) return;
            Map<String, long[]> m = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, long[]>>() {}.getType());
            if (m != null) {
                for (Map.Entry<String, long[]> e : m.entrySet()) {
                    long[] v = e.getValue();
                    if (v != null && v.length == 3) dedup.put(e.getKey(), new long[] {v[0], v[1], v[2], 0}); // 1.0.0.30 之前的三元组
                    else if (v != null && v.length == 4) dedup.put(e.getKey(), v);
                }
            }
        } catch (RuntimeException | IOException ignored) {
            // 状态坏了就从空开始
        }
    }

    private long lastDedupSave;

    /**
     * 存排重状态。重复错误刷屏时每条都在锁里重写整个文件太慢（主线程也会写日志），所以 force=false 时最多 5 秒存一次；
     * 新指纹、close() 立即存。清理 24 小时不活动的项之前，先把还没汇总的次数写成汇总（以前直接丢掉）。
     */
    private void saveDedup(long now, boolean force) throws IOException {
        if (!force && now - lastDedupSave < 5_000L) return;
        lastDedupSave = now;
        for (Map.Entry<String, long[]> e : new ArrayList<>(dedup.entrySet())) {
            long[] d = e.getValue();
            if (now - d[1] > DEDUP_IDLE_PURGE_MS && d[2] > Math.max(1, d[3])) writeSummary(e.getKey(), d, now);
        }
        dedup.entrySet().removeIf(e -> now - e.getValue()[1] > DEDUP_IDLE_PURGE_MS);
        if (dedup.size() > DEDUP_MAX_ENTRIES) {
            List<Map.Entry<String, long[]>> es = new ArrayList<>(dedup.entrySet());
            es.sort(Comparator.comparingLong(e -> e.getValue()[1]));
            for (int i = 0; i < es.size() - DEDUP_MAX_ENTRIES; i++) dedup.remove(es.get(i).getKey());
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve("error-dedup-state.json.tmp");
        Files.writeString(tmp, GSON.toJson(dedup), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve("error-dedup-state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, dir.resolve("error-dedup-state.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    static List<String> firstFrames(Throwable t, int n) {
        List<String> out = new ArrayList<>();
        for (StackTraceElement e : t.getStackTrace()) {
            if (out.size() >= n) break;
            out.add(e.toString());
        }
        return out;
    }
}
