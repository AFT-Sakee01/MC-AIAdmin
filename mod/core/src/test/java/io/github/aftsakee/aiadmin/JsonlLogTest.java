package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlLogTest {
    @TempDir
    Path dir;

    private List<JsonObject> lines(String name) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String l : Files.readAllLines(dir.resolve(name), StandardCharsets.UTF_8)) out.add(JsonParser.parseString(l).getAsJsonObject());
        return out;
    }

    @Test
    void fieldsAndRedaction() throws Exception {
        JsonlLog log = new JsonlLog(dir, null);
        log.info("m", "e", "你好", "cid-1", JsonlLog.data("api_key", "sk-secret", "Authorization", "Bearer x", "n", 3,
                "input_tokens", 120, "output_tokens", 7, "access_token", "t", "rcon-password", "p"));
        JsonObject o = lines("full.jsonl").get(0);
        for (String f : List.of("schema_version", "timestamp_utc", "timestamp_local", "timezone", "level", "process", "thread", "module", "event",
                "message", "correlation_id", "data")) {
            assertTrue(o.has(f), f);
        }
        assertEquals("[redacted]", o.getAsJsonObject("data").get("api_key").getAsString());
        assertEquals("[redacted]", o.getAsJsonObject("data").get("Authorization").getAsString());
        assertEquals("[redacted]", o.getAsJsonObject("data").get("access_token").getAsString());
        assertEquals("[redacted]", o.getAsJsonObject("data").get("rcon-password").getAsString());
        assertEquals(120, o.getAsJsonObject("data").get("input_tokens").getAsInt()); // 计数字段不能被误伤
        assertEquals(7, o.getAsJsonObject("data").get("output_tokens").getAsInt());
        assertFalse(Files.readString(dir.resolve("full.jsonl")).contains("sk-secret"));
    }

    @Test
    void dedupErrorsAndSummaryOnClose() throws Exception {
        JsonlLog log = new JsonlLog(dir, null);
        for (int i = 0; i < 5; i++) log.error("m", "boom", "失败 " + i + " 次", null, JsonlLog.data());
        log.fatal("m", "fatal", "致命", null, null);
        log.fatal("m", "fatal", "致命", null, null);
        assertEquals(3, lines("error.jsonl").size()); // 1 条 error（数字归一后同指纹）+ 2 条 fatal
        assertEquals(7, lines("full.jsonl").size());
        log.close();
        List<JsonObject> err = lines("error.jsonl");
        JsonObject sum = err.get(err.size() - 1);
        assertEquals("error_dedup_summary", sum.get("event").getAsString());
        assertEquals(5, sum.getAsJsonObject("data").get("count").getAsInt());
        assertTrue(Files.exists(dir.resolve("error-dedup-state.json")));
    }

    @Test
    void rotatesAndKeepsThreeArchives() throws Exception {
        JsonlLog log = new JsonlLog(dir, null, 4000);
        String pad = "x".repeat(300);
        for (int i = 0; i < 200; i++) {
            log.info("m", "e", pad + i, null, null);
            if (i % 40 == 0) Thread.sleep(2); // 归档名带毫秒时间戳
        }
        assertTrue(Files.size(dir.resolve("full.jsonl")) <= 4000);
        long gz;
        try (Stream<Path> s = Files.list(dir)) {
            gz = s.filter(p -> p.getFileName().toString().matches("full-2.*\\.jsonl\\.gz")).count();
        }
        assertEquals(3, gz);
        for (JsonObject o : lines("full.jsonl")) assertEquals("e", o.get("event").getAsString());
    }

    @Test
    void hugeLineIsTruncated() throws Exception {
        JsonlLog log = new JsonlLog(dir, null);
        log.info("m", "e", "y".repeat(JsonlLog.MAX_LINE_BYTES + 10), null, JsonlLog.data("big", "z".repeat(1000)));
        assertTrue(Files.size(dir.resolve("full.jsonl")) < 10_000);
        assertTrue(lines("full.jsonl").get(0).getAsJsonObject("data").get("truncated").getAsBoolean());
    }
}
