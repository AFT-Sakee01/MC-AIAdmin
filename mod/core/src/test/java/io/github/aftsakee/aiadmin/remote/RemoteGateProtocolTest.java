package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远程接口拆分后的部件直接单测（不走网络）：RemoteGate 的检查顺序、McpProtocol 的两种限流外形、ToolAccess 的只读默认拒绝。 */
class RemoteGateProtocolTest {
    static final String TOKEN = "t".repeat(43);

    @TempDir
    Path tmp;
    BotConfig cfg;
    Rules rules;
    JsonlLog log;
    AgentService svc;
    McpEvents events;
    McpProtocol protocol;

    @BeforeEach
    void setUp() {
        cfg = new BotConfig();
        rules = new Rules();
        log = new JsonlLog(tmp.resolve("logs"), null);
        Tools tools = new Tools();
        tools.add(Tools.schema("get_server_status", "状态").build(), (a, c) -> "{\"tps\":20}");
        svc = new AgentService(() -> cfg, () -> rules, new AgentService.Host() {
            public int onlinePlayers() {
                return 0;
            }

            public String snapshotJson() {
                return "{}";
            }

            public String serverLabel() {
                return "测试服";
            }

            public void alertAdmins(String m, boolean g) {
            }
        }, tools, (method, u, headers, body, timeout) -> {
            throw new IOException("本地 API 不该被调用");
        }, log, tmp.resolve("state"), new Prompts(tmp.resolve("config")), null);
        svc.start();
        events = new McpEvents(() -> cfg, log, tmp.resolve("mcp-events.json"), p -> true, p -> p, true,
                (u, b, h) -> new McpEvents.Sender.Reply(200, new byte[0]));
        protocol = new McpProtocol(() -> cfg, log, "test", new RemoteTools(() -> cfg, () -> rules, tools, svc, log), () -> events);
    }

    @AfterEach
    void tearDown() {
        events.close();
        svc.stop();
    }

    private static MiniHttp.Request req(String body, Map<String, String> headers) {
        Map<String, String> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
        return new MiniHttp.Request("POST", "/mcp", h, body.getBytes(StandardCharsets.UTF_8), InetAddress.getLoopbackAddress());
    }

    private static JsonObject json(MiniHttp.Response r) {
        return JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 限流外形按层次区分：events/* 超过每分钟上限回 HTTP 429 + Retry-After（JSON-RPC -32000）；tools/call 超限回 isError 工具结果；两者都写明几秒后可重试。 */
    @Test
    void eventWritesOverLimitAre429ButToolCallsGetIsErrorResult() {
        cfg.remote.maxCallsPerMinute = 1;
        String sub = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"events/subscribe\",\"params\":{}}";
        MiniHttp.Response first = protocol.handle(req(sub, Map.of()), "who", "token");
        assertEquals(200, first.status());
        assertEquals(JsonRpc.INVALID_PARAMS, json(first).getAsJsonObject("error").get("code").getAsInt(), "参数错，但用掉了这一分钟的名额");

        MiniHttp.Response limited = protocol.handle(req(sub, Map.of()), "who", "token");
        assertEquals(429, limited.status());
        long retry = Long.parseLong(limited.headers().get("Retry-After"));
        assertTrue(retry >= 1 && retry <= 61, "Retry-After " + retry);
        JsonObject err = json(limited).getAsJsonObject("error");
        assertEquals(JsonRpc.RATE_LIMITED, err.get("code").getAsInt());
        assertTrue(err.get("message").getAsString().contains("太频繁") && err.get("message").getAsString().contains(retry + " 秒后再试"), err.toString());
        assertEquals(1, json(limited).get("id").getAsInt());

        // 新版协议同样是 429
        JsonObject meta = new JsonObject();
        meta.addProperty(McpProtocol.META_VERSION, McpProtocol.MODERN);
        meta.add(McpProtocol.META_CAPS, new JsonObject());
        JsonObject params = new JsonObject();
        params.add("_meta", meta);
        MiniHttp.Response modern = protocol.handle(req("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"events/unsubscribe\",\"params\":" + params + "}",
                Map.of("MCP-Protocol-Version", McpProtocol.MODERN, "Mcp-Method", "events/unsubscribe")), "who", "token");
        assertEquals(429, modern.status());
        assertTrue(modern.headers().containsKey("Retry-After"));

        // 旧版批量：整批 200（别的条目可能已执行），被限流的那一条带 -32000
        MiniHttp.Response batch = protocol.handle(req("[" + sub + ",{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}]", Map.of()), "who", "token");
        assertEquals(200, batch.status());
        JsonArray items = JsonParser.parseString(new String(batch.body(), StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(JsonRpc.RATE_LIMITED, items.get(0).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        assertTrue(items.get(1).getAsJsonObject().has("result"));

        // 工具调用超限：200 + isError 工具结果，模型能看到原因和重试时间
        MiniHttp.Response call = protocol.handle(req("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_server_status\",\"arguments\":{}}}",
                Map.of()), "who", "token");
        assertEquals(200, call.status());
        assertFalse(call.headers().containsKey("Retry-After"));
        JsonObject result = json(call).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("太频繁") && text.contains("秒后再试"), text);
    }

    /** 顺序：来源 → Origin → 令牌。Origin: null 一律拒绝（带有效令牌也一样），且不计入认证失败；401 / 429 在 early 和 handle 里外形相同。 */
    @Test
    void gateRejectsNullOriginBeforeTokenAndSharesUnauthorizedReplies() {
        cfg.remote.authFailLimit = 2;
        RemoteGate gate = new RemoteGate(() -> cfg, log, () -> TOKEN, () -> null, () -> "http://127.0.0.1:25580/mcp",
                (r, who, principal) -> MiniHttp.Response.json(200, "{\"who\":\"" + who + "\",\"principal\":\"" + principal + "\"}"));
        String xff = "203.0.113.5";
        for (int i = 0; i < 5; i++) {
            MiniHttp.Request bad = req("{}", Map.of("Origin", "null", "Authorization", "Bearer wrong-token-xxxxxxxx", "X-Forwarded-For", xff));
            assertEquals(403, gate.early(bad).status());
            assertEquals(403, gate.handle(bad).status());
        }
        assertEquals(403, gate.early(req("{}", Map.of("Origin", "null", "Authorization", "Bearer " + TOKEN))).status(), "有效令牌也不放行 Origin: null");
        assertEquals(403, gate.early(req("{}", Map.of("Origin", "https://evil.example", "Authorization", "Bearer " + TOKEN))).status());

        MiniHttp.Response noAuth = gate.early(req("{}", Map.of("X-Forwarded-For", xff)));
        assertEquals(401, noAuth.status());
        assertEquals("Bearer", noAuth.headers().get("WWW-Authenticate"));
        MiniHttp.Response wrong = gate.early(req("{}", Map.of("Authorization", "Bearer wrong-token-xxxxxxxx", "X-Forwarded-For", xff)));
        assertEquals(401, wrong.status(), "坏 Origin 没有计入认证失败：第一次真正的失败还没到封禁");
        assertEquals(401, gate.handle(req("{}", Map.of("Authorization", "Bearer wrong-token-xxxxxxxx", "X-Forwarded-For", xff))).status());
        MiniHttp.Response blocked = gate.handle(req("{}", Map.of("Authorization", "Bearer wrong-token-xxxxxxxx", "X-Forwarded-For", xff)));
        assertEquals(429, blocked.status(), "early 和 handle 的失败计入同一个 AuthGuard");
        assertTrue(Long.parseLong(blocked.headers().get("Retry-After")) > 0);

        MiniHttp.Request ok = req("{}", Map.of("Origin", "http://localhost:3000", "Authorization", "Bearer " + TOKEN, "X-Forwarded-For", xff));
        assertNull(gate.early(ok), "持有效令牌：封禁中也照常读请求体");
        MiniHttp.Response passed = gate.handle(ok);
        assertEquals(200, passed.status());
        assertTrue(json(passed).get("principal").getAsString().equals("token") && json(passed).get("who").getAsString().equals(xff));
    }

    /** 只读模式默认拒绝：不在只读白名单里的（包括没登记的新工具）都隐藏，写操作通用入口也隐藏；可写模式只隐藏 rules.json 的，且不遍历工具表。 */
    @Test
    void toolAccessHiddenIsDefaultDeny() {
        List<String> offered = List.of("get_server_status", "run_command", "brand_new_tool", "memory_read", "list_macros");
        Set<String> ro = ToolAccess.hidden(Set.of("get_server_status"), false, () -> offered);
        assertTrue(ro.containsAll(Set.of("get_server_status", "run_command", "brand_new_tool", ToolAccess.CALL_ACTION)), ro.toString());
        for (String visible : List.of("memory_read", "list_macros", ToolAccess.LIST, ToolAccess.CALL_READ, ToolAccess.STATUS, ToolAccess.QUOTA)) {
            assertFalse(ro.contains(visible), visible);
        }
        AtomicBoolean listed = new AtomicBoolean();
        Set<String> rw = ToolAccess.hidden(Set.of("send_chat"), true, () -> {
            listed.set(true);
            return offered;
        });
        assertEquals(Set.of("send_chat"), rw);
        assertFalse(listed.get(), "可写模式不遍历工具表");
    }
}
