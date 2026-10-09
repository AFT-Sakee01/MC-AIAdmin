package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ConfigStore;
import io.github.aftsakee.aiadmin.config.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndRulesTest {
    @TempDir
    Path dir;

    @Test
    void createsDefaultsAndReloads() throws Exception {
        Path cf = dir.resolve("config.json");
        var a = ConfigStore.loadConfig(cf);
        assertTrue(a.created());
        JsonObject j = JsonParser.parseString(Files.readString(cf)).getAsJsonObject();
        assertTrue(j.has("active_provider"));                 // snake_case
        assertTrue(j.getAsJsonObject("providers").has("agy"));
        assertFalse(Files.readString(cf).contains("deny_roots")); // 规则不在 config.json 里
        var b = ConfigStore.loadConfig(cf);
        assertFalse(b.created());
        assertEquals("deepseek", b.value().activeProvider);
        assertEquals(4, b.value().providers.size());

        Path rf = dir.resolve("rules.json");
        var r = ConfigStore.loadRules(rf, b.value());
        assertTrue(r.created());
        String rt = Files.readString(rf);
        assertTrue(rt.contains("\"permissions\"") && rt.contains("\"quota\"") && rt.contains("\"schedule\""));
        assertTrue(rt.contains("deny_roots") && rt.contains("provider_balance") && rt.contains("active_hours"));
        Rules rr = ConfigStore.loadRules(rf, b.value()).value();
        assertTrue(rr.permissions.denyRoots.contains("stop"));
        assertEquals(1.0, rr.quota.providerBalance.get("deepseek").stopBelow);
    }

    /** remote.public_base_url 决定 OAuth 发现信息里的地址：只能是 https（或本机 http），不能带路径；末尾的 / 去掉。 */
    @Test
    void publicBaseUrlValidation() throws Exception {
        Path cf = dir.resolve("config.json");
        Files.writeString(cf, "{\"remote\":{\"public_base_url\":\"https://myserver.example-tailnet.ts.net/\",\"oauth\":null}}");
        BotConfig ok = ConfigStore.loadConfig(cf).value();
        assertEquals("https://myserver.example-tailnet.ts.net", ok.remote.publicBaseUrl);
        assertTrue(ok.remote.oauth.enabled);
        assertTrue(ok.remote.oauth.allowedRedirectHosts.contains("chatgpt.com"));
        Files.writeString(cf, "{\"remote\":{\"public_base_url\":\"https://myserver.example-tailnet.ts.net:443/\"}}");
        assertEquals("https://myserver.example-tailnet.ts.net", ConfigStore.loadConfig(cf).value().remote.publicBaseUrl); // 浏览器的 Origin 不带 :443
        Files.writeString(cf, "{\"remote\":{\"public_base_url\":\"https://a.example:8443\",\"oauth\":{\"refresh_token_days\":400,\"refresh_max_days\":90}}}");
        BotConfig p = ConfigStore.loadConfig(cf).value();
        assertEquals("https://a.example:8443", p.remote.publicBaseUrl);
        assertEquals(90, p.remote.oauth.refreshTokenDays); // 单个 refresh 的期限不能超过绝对上限
        Files.writeString(cf, "{\"remote\":{\"public_base_url\":\"http://127.0.0.1:25580\"}}");
        assertEquals("http://127.0.0.1:25580", ConfigStore.loadConfig(cf).value().remote.publicBaseUrl);
        for (String bad : List.of("http://example.com", "https://a.example/mcp", "https://a.example?x=1", "ftp://a.example", "https://u@a.example", "not a url")) {
            Files.writeString(cf, "{\"remote\":{\"public_base_url\":\"" + bad + "\"}}");
            assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf), bad);
        }
    }

    @Test
    void brokenFileIsNeverOverwritten() throws Exception {
        Path cf = dir.resolve("config.json");
        Files.writeString(cf, "{ \"providers\": oops", StandardCharsets.UTF_8);
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf));
        assertEquals("{ \"providers\": oops", Files.readString(cf));
    }

    @Test
    void validatesProvidersAndRules() throws Exception {
        Path cf = dir.resolve("config.json");
        Files.writeString(cf, "﻿{\"active_provider\":\"nope\"}", StandardCharsets.UTF_8);
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf));
        Files.writeString(cf, "{\"providers\":{\"x\":{\"type\":\"weird\",\"base_url\":\"https://a\"}},\"active_provider\":\"x\"}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf));

        BotConfig c = new BotConfig();
        Path rf = dir.resolve("rules.json");
        Files.writeString(rf, "{\"schedule\":{\"active_hours\":[\"25:00-02:00\"]}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadRules(rf, c));
        Files.writeString(rf, "{\"quota\":{\"windows\":[{\"period\":\"year\",\"max_runs\":1}]}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadRules(rf, c));
        Files.writeString(rf, "{\"permissions\":{\"deny_patterns\":[\"(\"]}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadRules(rf, c));
        // 省略的部分用默认值
        Files.writeString(rf, "{\"permissions\":{\"allow_chat\":false}}");
        Rules r = ConfigStore.loadRules(rf, c).value();
        assertFalse(r.permissions.allowChat);
        assertTrue(r.permissions.hiddenTools().contains("send_chat"));
        assertEquals(12, r.quota.perRun.maxSteps);
        assertEquals(120, r.schedule.startupDelaySeconds);
    }

    /** 审查发现：JSON 里的 null 会变成 NPE 逃出 reload（开服时可能直接起不来）。 */
    @Test
    void nullsBecomeConfigErrorsOrAreDropped() throws Exception {
        Path cf = dir.resolve("config.json");
        ConfigStore.loadConfig(cf);
        String base = Files.readString(cf);
        Files.writeString(cf, base.replaceFirst("\"type\": \"digest\"", "\"type\": null"));
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf));
        Files.writeString(cf, base.replace("\"chat_keywords\": []", "\"chat_keywords\": [null, \"@AI\"]")
                .replace("\"compress_when_over\": 30", "\"compress_when_over\": 50"));
        BotConfig c = ConfigStore.loadConfig(cf).value();
        assertEquals(List.of("@AI"), c.events.chatKeywords);
        assertEquals(49, c.compression.memory.compressWhenOver); // 50 永远触发不了，压到 49

        Path rf = dir.resolve("rules.json");
        Files.writeString(rf, "{\"quota\":{\"windows\":[{\"period\":null,\"max_runs\":1}]}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadRules(rf, c));
        Files.writeString(rf, "{\"permissions\":{\"deny_patterns\":[null,\"^x\"],\"deny_roots\":[null]}}");
        Rules r = ConfigStore.loadRules(rf, c).value();
        assertEquals(List.of("^x"), r.permissions.denyPatterns);
        assertTrue(r.permissions.denyRoots.isEmpty());
    }

    @Test
    void chatLimitsAndSafetyOffFromJson() throws Exception {
        BotConfig c = new BotConfig();
        Path rf = dir.resolve("rules.json");
        Files.writeString(rf, """
                {"permissions": {"chat_safety_off": {"ops": true, "players": ["Steve", null]}},
                 "quota": {"chat_limits": {"defaults": {"max_per_hour": 5},
                                           "normal_players": {"keyword_cooldown_seconds": 60},
                                           "players": {"Steve": {"max_per_day": 3, "ignore": false}, "Bad": null}}}}""");
        Rules r = ConfigStore.loadRules(rf, c).value();
        assertTrue(r.permissions.chatSafetyOff.appliesTo("steve", false) && r.permissions.chatSafetyOff.appliesTo("x", true));
        assertEquals(List.of("Steve"), r.permissions.chatSafetyOff.players);
        var e = r.quota.chatLimits.resolve("STEVE", false, 30, 8, 8);
        assertEquals(5, e.maxPerHour());          // 继承 defaults
        assertEquals(3, e.maxPerDay());           // 个人
        assertEquals(60, e.keywordCooldownSeconds()); // 普通玩家组
        assertEquals(8, e.playerCooldownSeconds());   // 继承 config.json
        assertFalse(r.quota.chatLimits.players.containsKey("Bad"));
        Files.writeString(rf, "{\"quota\":{\"chat_limits\":{\"ops\":{\"max_per_hour\":-1}}}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadRules(rf, c));
        Path cf = dir.resolve("config.json");
        Files.writeString(cf, "{\"chat\":{\"mode\":\"custom\",\"conditions\":[{\"type\":\"regex\",\"pattern\":\"(\"}]}}");
        assertThrows(ConfigStore.ConfigException.class, () -> ConfigStore.loadConfig(cf));
        Files.writeString(cf, "{\"chat\":{\"mode\":\"custom\",\"conditions\":[{\"type\":\"burst\",\"more_than\":5,\"window_seconds\":120,\"cooldown_seconds\":60},"
                + "{\"type\":\"question\",\"cooldown_seconds\":10}]}}");
        BotConfig loaded = ConfigStore.loadConfig(cf).value();
        assertEquals("custom", loaded.chat.mode);
        assertEquals(60, loaded.chat.conditions.get(0).cooldownSeconds);
        assertEquals(10, loaded.chat.conditions.get(1).cooldownSeconds);
    }

    @Test
    void promptFilesPerProvider() throws Exception {
        BotConfig c = new BotConfig();
        Prompts p = new Prompts(dir);
        List<Path> created = p.ensure(c);
        assertEquals(14, created.size()); // 4 个接入点 × 系统/自定义 + 2 个压缩提示词 + 3 个预设模式 + 服务器指南
        assertTrue(Files.exists(dir.resolve("prompts/deepseek.system.md")));
        assertTrue(Files.exists(dir.resolve("prompts/agy.custom.md")));
        assertTrue(p.ensure(c).isEmpty()); // 已存在不覆盖

        String sys = p.system("deepseek", c.providers.get("deepseek"), c, "测试服");
        assertTrue(sys.contains("「测试服」") && sys.contains("「AI管家」"));
        assertFalse(sys.contains("<!--"));
        assertFalse(sys.contains("服主的补充规则")); // 默认自定义提示词只有注释

        Files.writeString(dir.resolve("prompts/deepseek.custom.md"), "<!-- 注释 -->\n禁止矿透。{bot_name} 要活泼。", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("prompts/deepseek.system.md"), "你是 {bot_name}。", StandardCharsets.UTF_8);
        sys = p.system("deepseek", c.providers.get("deepseek"), c, "测试服");
        assertTrue(sys.startsWith("你是 AI管家。\n\n服主的补充规则：\n禁止矿透。AI管家 要活泼。\n\n指令："), sys); // 代码追加的指令资料说明在后面
        // 其他接入点不受影响
        assertTrue(p.system("claude", c.providers.get("claude"), c, "S").contains("你影响服务器的方式只有两种"));

        // 自定义路径
        c.providers.get("gpt").systemPromptFile = "my/gpt-sys.md";
        p.ensure(c);
        assertTrue(Files.exists(dir.resolve("my/gpt-sys.md")));
    }
}
