package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.CommandNotes;
import io.github.aftsakee.aiadmin.agent.PlayerTracker;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ConfigStore;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Providers;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.policy.PrankPolicy;
import io.github.aftsakee.aiadmin.util.FileGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.0.0.31 杂项修复：设置写错的提示、文件与日志脱敏、日志排重规则、漂浮、指令笔记、协议容错、玩家名大小写。 */
class MiscFixesTest {
    @TempDir
    Path tmp;

    private List<JsonObject> lines(Path f) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) out.add(JsonParser.parseString(l).getAsJsonObject());
        return out;
    }

    @Test
    void configTyposAndWrongTypesAreReported() throws Exception {
        Path f = tmp.resolve("config.json");
        JsonObject o = ConfigStore.GSON.toJsonTree(new BotConfig()).getAsJsonObject();
        o.getAsJsonObject("patrol").addProperty("interval_minute", 5);
        o.addProperty("enabled", "yes");
        o.getAsJsonObject("remote").addProperty("port", 70000);
        o.getAsJsonObject("providers").getAsJsonObject("deepseek").addProperty("modle", "x");
        Files.writeString(f, o.toString());
        ConfigStore.Loaded<BotConfig> l = ConfigStore.loadConfig(f);
        String w = String.join("\n", l.warnings());
        assertTrue(w.contains("patrol.interval_minute"), w);
        assertTrue(w.contains("enabled") && w.contains("true 或 false"), w);
        assertTrue(w.contains("remote.port") && w.contains("70000") && w.contains("65535"), w);
        assertTrue(w.contains("providers.deepseek.modle"), w);
        Files.writeString(f, ConfigStore.GSON.toJson(new BotConfig()));
        assertTrue(ConfigStore.loadConfig(f).warnings().isEmpty(), "默认设置没有任何警告：" + ConfigStore.loadConfig(f).warnings());
        Path r = tmp.resolve("rules.json");
        Files.writeString(r, ConfigStore.GSON.toJson(new Rules()));
        assertTrue(ConfigStore.loadRules(r, new BotConfig()).warnings().isEmpty(), "默认规则没有警告");
    }

    @Test
    void fileRedactionCoversInlineSecretsAndSecretFiles() {
        List<String> in = List.of("{\"api_key\":\"sk-123456\",\"max_tokens\":100}", "export API_KEY=abc123", "Authorization: Bearer abcdefgh12345",
                "url=jdbc:mysql://root:hunter2@db:3306/x", "-Dapp.password=pw1 -Xmx2G", "  - token: abc");
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) FileGuard.redact(in).get(0);
        String all = String.join("\n", out);
        for (String s : List.of("sk-123456", "abc123", "abcdefgh12345", "hunter2", "pw1")) assertFalse(all.contains(s), s + " → " + all);
        assertTrue(all.contains("\"max_tokens\":100"), "计数字段 max_tokens 不算密钥：" + all);
        assertTrue(FileGuard.denied("plugins/velocity/forwarding.secret", List.of()));
        assertTrue(FileGuard.denied(".env", List.of()));
        assertFalse(FileGuard.denied("server.properties", List.of()));
    }

    @Test
    void logRedactionIsRecursiveAndCoversMessages() throws Exception {
        Path dir = tmp.resolve("logs");
        JsonlLog log = new JsonlLog(dir, null);
        log.info("m", "e", "请求头 Authorization: Bearer abcdefghijkl 和 sk-ABCDEFGHIJKLMNOP", null,
                JsonlLog.data("x-api-key", "k1", "accessToken", "k2", "headers", Map.of("Authorization", "Bearer zzzzzzzzzz"),
                        "list", List.of("sk-QQQQQQQQQQQQQQQQ"), "input_tokens", 5, "token_usage", Map.of("input", 3)));
        String text = Files.readString(dir.resolve("full.jsonl"));
        for (String s : List.of("abcdefghijkl", "ABCDEFGHIJKLMNOP", "k1\"", "k2\"", "zzzzzzzzzz", "QQQQQQQQ")) assertFalse(text.contains(s), s + " → " + text);
        JsonObject d = lines(dir.resolve("full.jsonl")).get(0).getAsJsonObject("data");
        assertEquals(5, d.get("input_tokens").getAsInt());
        assertEquals(3, d.getAsJsonObject("token_usage").get("input").getAsInt(), "token_usage 不是密钥");
        log.info("m", "e", "Path 值", null, JsonlLog.data("path", tmp));
        assertEquals(2, lines(dir.resolve("full.jsonl")).size(), "序列化不了的值退回字符串，整行不丢");
    }

    @Test
    void unhandledExceptionsAreNeverDeduplicatedAndLongBurstsKeepTheWindow() throws Exception {
        Path dir = tmp.resolve("logs");
        JsonlLog log = new JsonlLog(dir, null);
        log.unhandled("agent", "run.crash", "运行时出现未处理异常", null, new IllegalStateException("x"));
        log.unhandled("agent", "run.crash", "运行时出现未处理异常", null, new IllegalStateException("x"));
        assertEquals(2, lines(dir.resolve("error.jsonl")).size(), "未处理异常每次都写");
        for (int i = 0; i < 250; i++) log.error("m", "boom", "失败 " + i, null, JsonlLog.data());
        List<JsonObject> err = lines(dir.resolve("error.jsonl"));
        long firsts = err.stream().filter(o -> o.get("event").getAsString().equals("boom")).count();
        long sums = err.stream().filter(o -> o.get("event").getAsString().equals("error_dedup_summary")).count();
        assertEquals(1, firsts, "10 分钟窗口内只写首条（第 100 次不再重开窗口）");
        assertEquals(2, sums, "每多 100 次写一条汇总");
        log.close();
        JsonObject last = lines(dir.resolve("error.jsonl")).get(lines(dir.resolve("error.jsonl")).size() - 1);
        assertEquals(250, last.getAsJsonObject("data").get("count").getAsInt());
    }

    @Test
    void levitationIsLimitedToLevelZero() {
        assertFalse(PrankPolicy.check("effect give Steve levitation 3 2", PrankPolicy.Level.HARMLESS, Set.of(), () -> 0, 3).allowed());
        assertFalse(PrankPolicy.check("effect give Steve levitation 6 3", PrankPolicy.Level.ROUGH, Set.of(), () -> 0, 3).allowed());
        assertTrue(PrankPolicy.check("effect give Steve levitation 3 0", PrankPolicy.Level.HARMLESS, Set.of(), () -> 0, 3).allowed());
        assertTrue(PrankPolicy.check("effect give Steve speed 5 2", PrankPolicy.Level.HARMLESS, Set.of(), () -> 0, 3).allowed());
    }

    @Test
    void commandNotesRespectNamespacesAndExecuteLayers() {
        AtomicLong now = new AtomicLong(1_000_000L);
        CommandNotes n = new CommandNotes(tmp.resolve("notes.json"), now::get);
        n.recordRun("othermod:pig Steve 1", true);
        assertTrue(n.add("pig", "单位是秒", true, "AI").contains("未验证"), "别的模组的同名指令不算");
        n.recordRun("execute as Steve run pig Steve 1", true);
        String r = n.add("pig", "单位是秒", true, "AI");
        assertTrue(r.contains("已验证") && r.contains("只证明"), r);
        assertTrue(n.add("othermod:pig", "x", true, "AI").contains("已验证"), "带命名空间的指令也能记笔记");
    }

    private static ProviderConfig claude() {
        ProviderConfig p = new ProviderConfig();
        p.type = ProviderConfig.ANTHROPIC;
        p.baseUrl = "https://api.example/";
        p.apiKey = "k";
        p.model = "m";
        p.maxTokens = 100;
        return p;
    }

    @Test
    void malformedSuccessRepliesAreErrors() throws Exception {
        HttpTransport err = (m, u, h, b, t) -> new HttpTransport.Response(200, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
        Llm.Session s = Providers.open(claude(), List.of(), err);
        assertThrows(Llm.LlmException.class, () -> s.start("sys", "hi"));
        HttpTransport empty = (m, u, h, b, t) -> new HttpTransport.Response(200, "{\"id\":\"x\"}");
        assertThrows(Llm.LlmException.class, () -> Providers.open(claude(), List.of(), empty).start("sys", "hi"));
    }

    @Test
    void playerNamesAreCaseInsensitiveInTracker() {
        PlayerTracker t = new PlayerTracker(30);
        t.join("Steve", 1000);
        assertTrue(t.known("steve") && t.known("STEVE"), "任何大小写都能查到 Steve");
    }

    @Test
    void goalCheckResultsAreSavedOncePerRound() throws Exception {
        Path f = tmp.resolve("goals-progress.json");
        io.github.aftsakee.aiadmin.goals.GoalStore g = new io.github.aftsakee.aiadmin.goals.GoalStore(f);
        g.update("Steve", p -> p.stagesDone.put("wood", 1L));
        assertFalse(Files.exists(f), "定期检查的结果不立即写盘");
        g.saveIfDirty();
        assertTrue(Files.readString(f).contains("wood"));
        assertTrue(new io.github.aftsakee.aiadmin.goals.GoalStore(f).get("steve").stagesDone.containsKey("wood"));
    }
}
