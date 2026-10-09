package io.github.aftsakee.aiadmin.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

/**
 * 模组自己的 JSON 状态文件（额度账本、记忆、偏好、工程、目标进度……）的统一读写。
 * <ul>
 *   <li>写：同目录唯一临时文件 + 原子改名，写一半断电不会留下坏文件；并发写同一文件不会互相删掉临时文件。</li>
 *   <li>读坏不覆盖（AGENTS.md 约束）：解析失败时先把原文件改名为 {@code <名字>.corrupt-<UTC时间>}，再按"没有数据"继续——
 *       否则下一次保存会用空数据覆盖原文件，坏文件里能救回的数据和出错证据都没了。</li>
 *   <li>出错上报：读坏、隔离、写失败都交给 {@link #setErrorSink} 设置的回调（服务启动时接到 JsonlLog），
 *       状态类本身不依赖日志，单测里不设置也不会出错。回调自己抛异常会被吞掉——写日志失败不能让存档流程崩溃。</li>
 * </ul>
 * 服主手写的设置文件（config.json、rules.json、goals.json）不走这里：它们读坏时要锁定或沿用旧设置，见 ConfigStore。
 */
public final class JsonFiles {
    private JsonFiles() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static volatile BiConsumer<String, String> sink;

    /** 读取结果：json 为 null 表示没有可用数据（文件不存在，或读坏已隔离——此时 error 非空）。 */
    public record Load(JsonElement json, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 出错回调：(事件, 说明)。传 null 取消。 */
    public static void setErrorSink(BiConsumer<String, String> s) {
        sink = s;
    }

    static void report(String event, String msg) {
        BiConsumer<String, String> s = sink;
        if (s == null) return;
        try {
            s.accept(event, msg);
        } catch (RuntimeException ignored) {
            // 日志回调失败不影响存档
        }
    }

    /** 文件不存在返回 null；解析失败抛异常（调用方自己处理）。新代码优先用 {@link #load}。 */
    public static JsonElement read(Path f) throws IOException {
        if (f == null || !Files.isRegularFile(f)) return null;
        return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
    }

    /**
     * 读状态文件。不存在 → (null, null)；读不了或不是合法 JSON → 原文件改名隔离后返回 (null, 说明)。
     * 调用方拿到 json 后逐条解析时如果发现个别记录坏了，应跳过那一条而不是整份丢弃；整份结构不对时调用 {@link #quarantine}。
     */
    public static Load load(Path f) {
        if (f == null || !Files.isRegularFile(f)) return new Load(null, null);
        try {
            return new Load(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)), null);
        } catch (IOException | RuntimeException e) {
            return new Load(null, quarantine(f, e.getClass().getSimpleName() + "：" + e.getMessage()));
        }
    }

    /** 把读坏的文件改名为 .corrupt-时间戳 并上报，返回给调用方/状态显示用的说明。 */
    public static String quarantine(Path f, String why) {
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        Path bad = f.resolveSibling(f.getFileName() + ".corrupt-" + stamp);
        String msg;
        try {
            Files.move(f, bad, StandardCopyOption.REPLACE_EXISTING);
            msg = f.getFileName() + " 读不了（" + why + "），原文件已改名为 " + bad.getFileName() + "，这次按空数据开始";
        } catch (IOException e) {
            msg = f.getFileName() + " 读不了（" + why + "），改名保留也失败（" + e.getMessage() + "），这次按空数据开始";
        }
        report("state.corrupt", msg);
        return msg;
    }

    /**
     * 文件整体能读、但个别记录坏了被跳过时：复制一份 .corrupt-时间戳 留证（原文件不动，下次保存会去掉坏记录），并上报。返回说明。
     */
    public static String keepBadCopy(Path f, int skipped) {
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        Path bad = f.resolveSibling(f.getFileName() + ".corrupt-" + stamp);
        String msg;
        try {
            Files.copy(f, bad, StandardCopyOption.REPLACE_EXISTING);
            msg = f.getFileName() + " 里有 " + skipped + " 条记录读不了，已跳过（原样复制到 " + bad.getFileName() + "）";
        } catch (IOException e) {
            msg = f.getFileName() + " 里有 " + skipped + " 条记录读不了，已跳过（复制留证失败：" + e.getMessage() + "）";
        }
        report("state.partial", msg);
        return msg;
    }

    public static void write(Path f, String json) throws IOException {
        Path dir = f.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        // 唯一临时文件名：同一文件被两个线程同时保存时，固定的 .tmp 会被另一方改名拿走或删掉
        Path tmp = Files.createTempFile(dir, f.getFileName() + ".", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 写入；失败时上报并返回说明（成功返回 null）。给不想把 IOException 往上抛的状态类用。 */
    public static String writeQuietly(Path f, String json) {
        try {
            write(f, json);
            return null;
        } catch (IOException | RuntimeException e) {
            String msg = "保存 " + f.getFileName() + " 失败：" + e.getMessage();
            report("state.save_failed", msg);
            return msg;
        }
    }
}
