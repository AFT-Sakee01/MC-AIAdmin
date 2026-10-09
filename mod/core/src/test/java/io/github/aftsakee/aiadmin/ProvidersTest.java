package io.github.aftsakee.aiadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.AgentRunner;
import io.github.aftsakee.aiadmin.agent.Compressor;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.HttpTransport;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Providers;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import io.github.aftsakee.aiadmin.agent.Prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 三种协议的请求格式 + 工具循环（假 HTTP 脚本化回复）。 */
class ProvidersTest {
    @TempDir
    Path tmp;
    BotConfig cfg;
    Rules rules;
    Tools tools;
    JsonlLog log;
    final TestRig.FakeActions actions = new TestRig.FakeActions();
    final List<String> executed = actions.executed;
    final List<String> chats = actions.chats;
    Compressor comp = Compressor.OFF;

    @BeforeEach
    void setUp() {
        cfg = new BotConfig();
        rules = new Rules();
        log = new JsonlLog(tmp.resolve("logs"), null);
        tools = new Tools();
        tools.add(Tools.schema("get_server_status", "状态").build(), (a, c) -> "{\"tps\":20}");
        actions.outcome = c -> new ActionTools.ServerActions.CommandOutcome(true, 1, "OK " + c);
        ActionTools.register(tools, actions, () -> cfg, () -> rules, log, new Activity());
    }

    private ProviderConfig provider(String type, String base) {
        ProviderConfig p = new ProviderConfig();
        p.type = type;
        p.baseUrl = base;
        p.apiKey = "test-key";
        p.model = "m1";
        p.maxTokens = type.equals(ProviderConfig.ANTHROPIC) ? 1000 : 0;
        return p;
    }

    private AgentRunner.Result run(ProviderConfig p, HttpTransport http) throws Exception {
        RunContext ctx = new RunContext("cid-test", Trigger.patrol(true), cfg, rules, "p");
        return AgentRunner.run(() -> Providers.open(p, tools.specs(rules.permissions.hiddenTools()), http), "SYS", "USER", tools, ctx, log,
                System.currentTimeMillis() + 60_000, comp);
    }

    @Test
    void openAiToolLoop() throws Exception {
        FakeHttp http = new FakeHttp()
                .ok("""
                        {"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"思考",
                          "tool_calls":[
                            {"id":"c1","type":"function","function":{"name":"run_command","arguments":"{\\"command\\":\\"/time set day\\"}"}},
                            {"id":"c2","type":"function","function":{"name":"run_command","arguments":"{\\"command\\":\\"execute as @a run stop\\"}"}},
                            {"id":"c3","type":"function","function":{"name":"nope","arguments":"{}"}},
                            {"id":"c4","type":"function","function":{"name":"send_chat","arguments":"{bad json"}}]},
                          "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":20}}""")
                .ok("""
                        {"choices":[{"message":{"role":"assistant","content":"白天了"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":150,"completion_tokens":5}}""");
        AgentRunner.Result r = run(provider(ProviderConfig.OPENAI, "https://relay.example/v1"), http);

        assertEquals("白天了", r.text());
        assertNull(r.error());
        assertEquals(2, r.steps());
        assertEquals(275, r.usage().total());
        assertEquals(List.of("time set day"), executed); // stop 被拦
        FakeHttp.Req q1 = http.requests.get(0);
        assertEquals("https://relay.example/v1/chat/completions", q1.url());
        assertEquals("Bearer test-key", q1.headers().get("Authorization"));
        JsonObject b1 = q1.json();
        assertEquals("function", b1.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("auto", b1.get("tool_choice").getAsString());
        assertFalse(b1.has("max_tokens"));

        JsonArray msgs = http.requests.get(1).json().getAsJsonArray("messages");
        assertEquals(7, msgs.size()); // system, user, assistant, 4 × tool
        JsonObject asst = msgs.get(2).getAsJsonObject();
        assertEquals("思考", asst.get("reasoning_content").getAsString()); // DeepSeek 思考模式要求带回
        assertEquals(4, asst.getAsJsonArray("tool_calls").size());
        assertTrue(msgs.get(3).getAsJsonObject().get("content").getAsString().contains("\"allowed\":true"));
        assertTrue(msgs.get(4).getAsJsonObject().get("content").getAsString().contains("\"allowed\":false"));
        assertTrue(msgs.get(5).getAsJsonObject().get("content").getAsString().startsWith("错误：没有这个工具"));
        assertTrue(msgs.get(6).getAsJsonObject().get("content").getAsString().contains("不是合法 JSON"));
        assertEquals("c4", msgs.get(6).getAsJsonObject().get("tool_call_id").getAsString());
    }

    @Test
    void anthropicToolLoopKeepsThinkingBlocks() throws Exception {
        FakeHttp http = new FakeHttp()
                .ok("""
                        {"content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                                    {"type":"text","text":"我打个招呼"},
                                    {"type":"tool_use","id":"tu1","name":"send_chat","input":{"message":"大家好§c","player":"Steve"}}],
                         "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":3,"cache_read_input_tokens":5}}""")
                .ok("""
                        {"content":[{"type":"text","text":"完成"}],"stop_reason":"end_turn","usage":{"input_tokens":20,"output_tokens":2}}""");
        ProviderConfig p = provider(ProviderConfig.ANTHROPIC, "https://api.anthropic.com/");
        JsonObject oc = new JsonObject();
        oc.addProperty("effort", "low");
        p.extraBody.add("output_config", oc);
        AgentRunner.Result r = run(p, http);

        assertEquals("完成", r.text());
        assertEquals(List.of("Steve←大家好"), chats); // § 颜色代码被清掉
        FakeHttp.Req q1 = http.requests.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", q1.url());
        assertEquals("test-key", q1.headers().get("x-api-key"));
        assertEquals("2023-06-01", q1.headers().get("anthropic-version"));
        JsonObject b1 = q1.json();
        assertEquals("SYS", b1.get("system").getAsString());
        assertEquals(1000, b1.get("max_tokens").getAsInt());
        assertEquals("low", b1.getAsJsonObject("output_config").get("effort").getAsString());
        assertTrue(b1.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
        assertFalse(b1.has("temperature"));

        JsonArray msgs = http.requests.get(1).json().getAsJsonArray("messages");
        assertEquals(3, msgs.size());
        JsonArray asst = msgs.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals("sig-1", asst.get(0).getAsJsonObject().get("signature").getAsString()); // 思考块原样带回
        JsonObject tr = msgs.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_result", tr.get("type").getAsString());
        assertEquals("tu1", tr.get("tool_use_id").getAsString());
        assertEquals(40, r.usage().total());
    }

    @Test
    void anthropicBaseUrlWithV1AndBearerAuth() throws Exception {
        FakeHttp http = new FakeHttp().ok("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}");
        ProviderConfig p = provider(ProviderConfig.ANTHROPIC, "https://relay.example/v1");
        p.authStyle = "bearer";
        run(p, http);
        assertEquals("https://relay.example/v1/messages", http.requests.get(0).url());
        assertEquals("Bearer test-key", http.requests.get(0).headers().get("Authorization"));
        assertNull(http.requests.get(0).headers().get("x-api-key"));
    }

    @Test
    void anthropicRefusalRunsNoTools() throws Exception {
        FakeHttp http = new FakeHttp().ok("""
                {"content":[{"type":"tool_use","id":"x","name":"run_command","input":{"command":"say hi"}}],"stop_reason":"refusal"}""");
        AgentRunner.Result r = run(provider(ProviderConfig.ANTHROPIC, "https://api.anthropic.com"), http);
        assertTrue(executed.isEmpty());
        assertTrue(r.text().contains("拒绝"));
    }

    @Test
    void antigravityInteractionsLoop() throws Exception {
        FakeHttp http = new FakeHttp()
                .ok("""
                        {"id":"i1","status":"requires_action","environment_id":"env1",
                         "steps":[{"type":"function_call","id":"f1","name":"run_command","arguments":{"command":"weather clear"}}]}""")
                .ok("{\"id\":\"i2\",\"status\":\"in_progress\"}")
                .ok("""
                        {"id":"i2","status":"completed","output_text":"天晴了",
                         "steps":[{"type":"function_call","id":"f1","name":"run_command","arguments":{"command":"weather clear"}},
                                  {"type":"function_result","call_id":"f1"}],
                         "usage":{"input_tokens":30,"output_tokens":4}}""");
        ProviderConfig p = provider(ProviderConfig.ANTIGRAVITY, "https://generativelanguage.googleapis.com");
        p.model = "antigravity-preview-09-2026";
        AgentRunner.Result r = run(p, http);

        assertEquals("天晴了", r.text());
        assertEquals(List.of("weather clear"), executed);
        FakeHttp.Req q1 = http.requests.get(0);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", q1.url());
        assertEquals("test-key", q1.headers().get("x-goog-api-key"));
        JsonObject b1 = q1.json();
        assertEquals("antigravity-preview-09-2026", b1.get("agent").getAsString());
        assertEquals("remote", b1.get("environment").getAsString());
        for (var t : b1.getAsJsonArray("tools")) assertEquals("function", t.getAsJsonObject().get("type").getAsString()); // 内置工具默认关
        assertTrue(b1.getAsJsonArray("input").get(0).getAsJsonObject().get("text").getAsString().contains("SYS"));

        JsonObject b2 = http.requests.get(1).json();
        assertEquals("i1", b2.get("previous_interaction_id").getAsString());
        assertEquals("env1", b2.get("environment").getAsString());
        JsonObject fr = b2.getAsJsonArray("input").get(0).getAsJsonObject();
        assertEquals("function_result", fr.get("type").getAsString());
        assertEquals("f1", fr.get("call_id").getAsString());
        assertEquals("run_command", fr.get("name").getAsString());

        FakeHttp.Req poll = http.requests.get(2);
        assertEquals("GET", poll.method());
        assertTrue(poll.url().endsWith("/v1beta/interactions/i2"));
    }

    @Test
    void retriesGatewayErrorsButNotAuthErrors() throws Exception {
        FakeHttp http = new FakeHttp().then(503, "busy")
                .ok("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        assertEquals("ok", run(provider(ProviderConfig.OPENAI, "https://x/v1"), http).text());
        assertEquals(2, http.requests.size());

        FakeHttp bad = new FakeHttp().then(401, "{\"error\":\"invalid key\"}");
        Llm.LlmException e = assertThrows(Llm.LlmException.class, () -> run(provider(ProviderConfig.OPENAI, "https://x/v1"), bad));
        assertEquals(401, e.status());
        assertEquals(1, bad.requests.size());
    }

    @Test
    void missingKeyFailsBeforeAnyRequest() {
        ProviderConfig p = provider(ProviderConfig.OPENAI, "https://x/v1");
        p.apiKey = "";
        p.apiKeyEnv = "AIADMIN_TEST_SURELY_UNSET_ENV";
        FakeHttp http = new FakeHttp();
        assertThrows(Llm.LlmException.class, () -> run(p, http));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void commandLimitPerRun() throws Exception {
        rules.quota.perRun.maxCommands = 1;
        FakeHttp http = new FakeHttp()
                .ok("""
                        {"choices":[{"message":{"tool_calls":[
                          {"id":"a","type":"function","function":{"name":"run_command","arguments":"{\\"command\\":\\"say 1\\"}"}},
                          {"id":"b","type":"function","function":{"name":"run_command","arguments":"{\\"command\\":\\"say 2\\"}"}}]}}]}""")
                .ok("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
        run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertEquals(List.of("say 1"), executed);
    }

    @Test
    void stepLimitStopsLoop() throws Exception {
        rules.quota.perRun.maxSteps = 2;
        String loop = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"get_server_status\",\"arguments\":\"{}\"}}]}}]}";
        FakeHttp http = new FakeHttp().ok(loop).ok(loop).ok(loop);
        AgentRunner.Result r = run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertEquals(2, http.requests.size());
        assertTrue(r.error().contains("轮数上限"));
    }

    @Test
    void compressionRestartsWithDigest() throws Exception {
        cfg.compression.when.inputTokensOver = 1000;
        cfg.compression.method.keepRecentSteps = 1;
        cfg.compression.method.oldResultChars = 5;
        comp = new Compressor(cfg.compression, new io.github.aftsakee.aiadmin.agent.Prompts(tmp), null);
        String call = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a%d\",\"type\":\"function\",\"function\":{\"name\":\"run_command\","
                + "\"arguments\":\"{\\\"command\\\":\\\"say %d\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":%d,\"completion_tokens\":1}}";
        FakeHttp http = new FakeHttp()
                .ok(call.formatted(1, 1, 500))    // 第 1 轮：不压
                .ok(call.formatted(2, 2, 5000))   // 第 2 轮：输入 token 超阈值 → 压缩后重开
                .ok(call.formatted(3, 3, 300))    // 新会话第 1 轮
                .ok("{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}");
        AgentRunner.Result r = run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertEquals("完成", r.text());
        assertEquals(1, r.compressions());
        assertEquals(List.of("say 1", "say 2", "say 3"), executed);
        JsonArray restarted = http.requests.get(2).json().getAsJsonArray("messages");
        assertEquals(2, restarted.size()); // system + 带摘要的新 user，没有旧历史
        String u = restarted.get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(u.startsWith("USER") && u.contains("上下文已压缩") && u.contains("指令 say 1") && u.contains("指令 say 2"), u);
        assertTrue(u.contains("第 1 轮") && u.contains("第 2 轮"));
        assertEquals(4, http.requests.get(3).json().getAsJsonArray("messages").size()); // 新会话正常继续
    }

    @Test
    void summarizeMethodUsesSummarizerAndFallsBackOnFailure() throws Exception {
        cfg.compression.when.everySteps = 1;
        cfg.compression.method.type = "summarize";
        List<String> asked = new ArrayList<>();
        comp = new Compressor(cfg.compression, new io.github.aftsakee.aiadmin.agent.Prompts(tmp), (prov, ins, mat, ctx) -> {
            asked.add(prov + "|" + ins);
            return "【摘要】查过状态";
        });
        String call = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"get_server_status\",\"arguments\":\"{}\"}}]}}]}";
        FakeHttp http = new FakeHttp().ok(call).ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}]}");
        run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("p|") && asked.get(0).contains("对话压缩器") && asked.get(0).contains("3000"));
        assertTrue(http.requests.get(1).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("【摘要】查过状态"));

        comp = new Compressor(cfg.compression, new io.github.aftsakee.aiadmin.agent.Prompts(tmp), (prov, ins, mat, ctx) -> {
            throw new Llm.LlmException("坏了", 500);
        });
        FakeHttp http2 = new FakeHttp().ok(call).ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}]}");
        assertEquals("好", run(provider(ProviderConfig.OPENAI, "https://x/v1"), http2).text());
        assertTrue(http2.requests.get(1).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("调用 get_server_status"));
    }

    @Test
    void hiddenToolsAreNotOfferedNorExecutable() throws Exception {
        rules.permissions.allowCommands = false;
        FakeHttp http = new FakeHttp()
                .ok("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"say x\\\"}\"}}]}}]}")
                .ok("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertTrue(executed.isEmpty());
        for (var t : http.requests.get(0).json().getAsJsonArray("tools")) {
            assertFalse(t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString().equals("run_command"));
        }
    }

    /** 审查发现：回复被输出上限截断时，半截的工具调用不能执行。 */
    @Test
    void truncatedReplyRunsNoTools() throws Exception {
        FakeHttp http = new FakeHttp().ok("""
                {"content":[{"type":"tool_use","id":"x","name":"run_command","input":{"command":"kill @e[type=minecraft:it"}}],"stop_reason":"max_tokens"}""");
        AgentRunner.Result r = run(provider(ProviderConfig.ANTHROPIC, "https://api.anthropic.com"), http);
        assertTrue(executed.isEmpty());
        assertTrue(r.error().contains("截断"));
        FakeHttp http2 = new FakeHttp().ok("""
                {"choices":[{"message":{"tool_calls":[{"id":"a","type":"function","function":{"name":"run_command","arguments":"{\\"command\\":\\"say"}}]},"finish_reason":"length"}]}""");
        assertTrue(run(provider(ProviderConfig.OPENAI, "https://x/v1"), http2).error().contains("截断"));
        assertTrue(executed.isEmpty());
    }

    /** 审查发现：缺 function 字段的 tool_call 被抄进历史却没有对应回复，下一次请求会 400；数组参数会抛异常。 */
    @Test
    void malformedOpenAiToolCalls() throws Exception {
        FakeHttp http = new FakeHttp()
                .ok("""
                        {"choices":[{"message":{"tool_calls":[
                          {"id":"bad","type":"function"},
                          {"id":"arr","type":"function","function":{"name":"run_command","arguments":["say x"]}},
                          {"id":"ok","type":"function","function":{"name":"get_server_status","arguments":"{}"}}]}}]}""")
                .ok("{\"choices\":[{\"message\":{\"content\":\"好\"}}]}");
        assertEquals("好", run(provider(ProviderConfig.OPENAI, "https://x/v1"), http).text());
        JsonArray msgs = http.requests.get(1).json().getAsJsonArray("messages");
        assertEquals(2, msgs.get(2).getAsJsonObject().getAsJsonArray("tool_calls").size()); // 缺 function 的那条没进历史
        assertEquals(5, msgs.size()); // system、user、assistant、2 条 tool 回复
        assertTrue(msgs.get(3).getAsJsonObject().get("content").getAsString().contains("不是 JSON 对象"));
        assertTrue(executed.isEmpty());
    }

    /** 审查发现：第二次压缩时丢掉了第一次压缩的进展。 */
    @Test
    void secondCompressionKeepsEarlierProgress() throws Exception {
        cfg.compression.when.everySteps = 2;
        comp = new Compressor(cfg.compression, new io.github.aftsakee.aiadmin.agent.Prompts(tmp), null);
        String call = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a%d\",\"type\":\"function\",\"function\":{\"name\":\"run_command\","
                + "\"arguments\":\"{\\\"command\\\":\\\"say %d\\\"}\"}}]}}]}";
        FakeHttp http = new FakeHttp();
        for (int i = 1; i <= 4; i++) http.ok(call.formatted(i, i));
        http.ok("{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}");
        AgentRunner.Result r = run(provider(ProviderConfig.OPENAI, "https://x/v1"), http);
        assertEquals(2, r.compressions());
        String u = http.requests.get(4).json().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(u.contains("第 1–2 轮") && u.contains("第 3–4 轮") && u.contains("say 1") && u.contains("say 4"), u);
    }

    /** base_url 里有空格等非法字符时，真实传输层抛 IllegalArgumentException（由 AgentService 当作失败处理）。 */
    @Test
    void invalidBaseUrlThrowsRuntime() {
        assertThrows(IllegalArgumentException.class, () -> run(provider(ProviderConfig.OPENAI, "https://bad host/v1"), HttpTransport.jdk()));
    }

    /** 真实的 JDK HttpClient 发到本机假服务器，确认传输层能用。 */
    @Test
    void jdkTransportAgainstLocalServer() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        srv.createContext("/v1/chat/completions", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "{\"choices\":[{\"message\":{\"content\":\"来自本机\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        try {
            AgentRunner.Result r = run(provider(ProviderConfig.OPENAI, "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1"), HttpTransport.jdk());
            assertEquals("来自本机", r.text());
            assertEquals(List.of("Bearer test-key"), auth);
        } finally {
            srv.stop(0);
        }
    }
}
