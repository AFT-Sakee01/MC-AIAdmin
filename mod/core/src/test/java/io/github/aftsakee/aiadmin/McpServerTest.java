package io.github.aftsakee.aiadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.remote.AuthGuardAccess;
import io.github.aftsakee.aiadmin.remote.McpServer;
import io.github.aftsakee.aiadmin.util.Cidr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远程管理接口：鉴权、来源限制、MCP 握手、工具调用、规则与限流。走真实的本机 HTTP。 */
class McpServerTest {
    @TempDir
    Path tmp;
    TestRig rig;
    BotConfig cfg;
    Rules rules;
    AgentService svc;
    McpServer mcp;
    String url;
    String token;
    List<String> executed;
    List<String> chats;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        rig = new TestRig(tmp, false);
        rig.ensurePrompts = false;
        cfg = rig.cfg;
        rules = rig.rules;
        executed = rig.actions.executed;
        chats = rig.actions.chats;
        cfg.remote.enabled = true;
        cfg.remote.bind = "127.0.0.1";
        cfg.remote.port = 0; // 任意空闲端口（MiniHttp 绑定 0）
        rig.host.online = 0;
        rig.actions.outcome = c -> new ActionTools.ServerActions.CommandOutcome(true, 1, "Set the time to 13000");
        Tools tools = rig.tools;
        tools.add(Tools.schema("get_server_status", "状态").build(), (a, c) -> "{\"tps\":20}");
        rig.registerActions();
        io.github.aftsakee.aiadmin.agent.OwnerTools.register(tools, () -> tmp.resolve("config"), rig.log);
        svc = rig.start();
        mcp = new McpServer(() -> cfg, () -> rules, tools, svc, rig.log, "test");
        mcp.start(tmp.resolve("config"));
        long t0 = System.currentTimeMillis();
        while (mcp.url() == null && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        url = mcp.url();
        assertNotNull(url, mcp.describe());
        token = Files.readString(tmp.resolve("config/remote-token.txt")).trim();
    }

    @AfterEach
    void tearDown() {
        mcp.stop();
        rig.close();
    }

    private HttpResponse<String> post(String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject rpc(String method, String params) throws Exception {
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}", "Bearer " + token);
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private JsonObject call(String tool, String args) throws Exception {
        return rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}").getAsJsonObject("result");
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void tokenFileGeneratedAndAuthRequired() throws Exception {
        assertTrue(token.length() >= 40);
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null).statusCode());
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "Bearer wrong-token-xxxxxxxx").statusCode());
        assertEquals(200, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "Bearer " + token).statusCode());
        // 重启后沿用同一个令牌
        mcp.start(tmp.resolve("config"));
        long t0 = System.currentTimeMillis();
        while (mcp.url() == null && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        url = mcp.url();
        assertEquals(token, Files.readString(tmp.resolve("config/remote-token.txt")).trim());
    }

    @Test
    void initializeListAndCall() throws Exception {
        JsonObject init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        assertEquals("2025-06-18", init.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertTrue(init.getAsJsonObject("result").getAsJsonObject("capabilities").has("tools"));
        assertEquals("2025-06-18", rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "Bearer " + token).statusCode());

        JsonArray tools = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools");
        List<String> names = new ArrayList<>();
        for (var t : tools) names.add(t.getAsJsonObject().get("name").getAsString());
        assertTrue(names.containsAll(List.of("get_server_status", "run_command", "send_chat", "aibot_status", "aibot_quota")), names.toString());
        assertTrue(tools.get(0).getAsJsonObject().has("inputSchema"));

        assertEquals("{\"tps\":20}", text(call("get_server_status", "{}")));
        JsonObject ok = call("run_command", "{\"command\":\"time set night\"}");
        assertFalse(ok.get("isError").getAsBoolean());
        assertTrue(text(ok).contains("Set the time to 13000"));
        JsonObject denied = call("run_command", "{\"command\":\"execute run stop\"}");
        assertTrue(text(denied).contains("\"allowed\":false"));
        assertEquals(List.of("time set night"), executed);
        assertEquals("已发送", text(call("send_chat", "{\"message\":\"远程问好\"}")));
        assertEquals(List.of("远程问好"), chats);
        assertTrue(text(call("aibot_status", "{}")).contains("状态："));
        assertTrue(call("nope", "{}").get("isError").getAsBoolean());

        JsonObject unknown = rpc("resources/list", "{}");
        assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void genericEntrypointsReachEveryToolWithSameChecks() throws Exception {
        JsonArray tools = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools");
        java.util.Map<String, JsonObject> byName = new java.util.HashMap<>();
        for (var t : tools) byName.put(t.getAsJsonObject().get("name").getAsString(), t.getAsJsonObject());
        assertTrue(byName.keySet().containsAll(List.of("list_tools", "call_read_tool", "call_action_tool")), byName.keySet().toString());
        assertTrue(byName.get("get_server_status").getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertFalse(byName.get("run_command").getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertFalse(byName.get("server_power").getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertTrue(byName.get("call_read_tool").getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
        assertFalse(byName.get("call_action_tool").getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());

        String list = text(call("list_tools", "{}"));
        assertTrue(list.contains("\"name\":\"get_server_status\",\"access\":\"read\"") && list.contains("\"name\":\"run_command\",\"access\":\"action\""), list);
        assertFalse(list.contains("\"list_tools\""), "通用入口自己不列在里面");
        String one = text(call("list_tools", "{\"name\":\"run_command\"}"));
        assertTrue(one.contains("input_schema") && one.contains("call_action_tool"), one);

        assertEquals("{\"tps\":20}", text(call("call_read_tool", "{\"name\":\"get_server_status\",\"arguments\":{}}")));
        JsonObject sneaky = call("call_read_tool", "{\"name\":\"run_command\",\"arguments\":{\"command\":\"time set night\"}}");
        assertTrue(sneaky.get("isError").getAsBoolean() && text(sneaky).contains("call_action_tool"), "只读入口不能执行写操作");
        assertTrue(executed.isEmpty());

        assertTrue(text(call("call_action_tool", "{\"name\":\"run_command\",\"arguments\":{\"command\":\"time set night\"}}")).contains("Set the time"));
        assertTrue(text(call("call_action_tool", "{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"time set day\\\"}\"}")).contains("Set the time"),
                "arguments 被序列化成字符串也接受");
        assertTrue(text(call("call_action_tool", "{\"name\":\"run_command\",\"arguments\":{\"command\":\"execute run stop\"}}")).contains("\"allowed\":false"), "黑名单照样生效");
        assertEquals(List.of("time set night", "time set day"), executed);
        assertTrue(call("call_action_tool", "{\"name\":\"call_read_tool\",\"arguments\":{}}").get("isError").getAsBoolean(), "不能嵌套");
        assertTrue(call("call_action_tool", "{\"name\":\"run_command\",\"arguments\":[1]}").get("isError").getAsBoolean());
        assertTrue(call("call_read_tool", "{\"name\":\"nope\"}").get("isError").getAsBoolean());
    }

    @Test
    void macroCommandsCountTowardsRemoteHourlyLimit() throws Exception {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/macros.json"),
                "{\"macros\":{\"three\":{\"commands\":[\"time set day\",\"weather clear\",\"say hi\"]}}}");
        cfg.remote.maxCommandsPerHour = 2;
        String r = text(call("call_action_tool", "{\"name\":\"run_macro\",\"arguments\":{\"name\":\"three\"}}"));
        assertTrue(r.contains("\"completed\":true"), r);
        assertEquals(3, executed.size(), "本小时还有余量时宏整串执行");
        assertTrue(text(call("run_command", "{\"command\":\"say again\"}")).contains("已达上限"), "宏用掉的条数计入每小时上限");
        assertTrue(text(call("run_macro", "{\"name\":\"three\"}")).contains("已达上限"));
        assertEquals(3, executed.size());
        assertTrue(text(call("list_macros", "{}")).contains("three"), "只读的宏清单不受指令上限影响");
    }

    @Test
    void reloadWithUnchangedRemoteSettingsKeepsServing() throws Exception {
        String before = url;
        mcp.start(tmp.resolve("config"));
        assertEquals(before, mcp.url(), "远程接口设置没变：不重启，地址和连接都保留");
        assertEquals("{\"tps\":20}", text(call("get_server_status", "{}")));
        cfg.remote.maxCallsPerMinute = cfg.remote.maxCallsPerMinute + 1;
        mcp.start(tmp.resolve("config"));
        long t0 = System.currentTimeMillis();
        while (mcp.url() == null && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        url = mcp.url();
        assertEquals("{\"tps\":20}", text(call("get_server_status", "{}")), "设置变了照常重启并恢复服务");
    }

    @Test
    void readOnlyModeAndHiddenTools() throws Exception {
        cfg.remote.allowActions = false;
        rules.permissions.disabledTools = List.of("get_server_status");
        JsonArray tools = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools");
        String all = tools.toString();
        assertFalse(all.contains("\"run_command\"") || all.contains("\"send_chat\"") || all.contains("\"get_server_status\""), all);
        assertTrue(call("run_command", "{\"command\":\"say hi\"}").get("isError").getAsBoolean());
        assertFalse(all.contains("\"call_action_tool\"") || all.contains("\"server_power\""), "只读模式下写操作通用入口也隐藏");
        assertTrue(call("call_action_tool", "{\"name\":\"run_command\",\"arguments\":{\"command\":\"say hi\"}}").get("isError").getAsBoolean());
        String list = text(call("list_tools", "{}"));
        assertFalse(list.contains("\"run_command\"") || list.contains("\"get_server_status\""), "list_tools 不列被隐藏的工具：" + list);
        assertTrue(call("call_read_tool", "{\"name\":\"get_server_status\"}").get("isError").getAsBoolean(), "被服主禁用的只读工具也调不到");
        assertTrue(executed.isEmpty());
    }

    /** 只读模式是"默认拒绝"：不在只读白名单里的工具（宏、写记忆…）都隐藏，内部转调也被处理器兜底拦下。 */
    @Test
    void readOnlyModeHidesEveryNonReadTool() throws Exception {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/macros.json"), "{\"macros\":{\"kick\":{\"commands\":[\"kick Steve\"]}}}");
        cfg.remote.allowActions = false;
        String all = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools").toString();
        assertFalse(all.contains("\"run_macro\"") || all.contains("\"memory_note\"") || all.contains("\"reply_chat\""), all);
        assertTrue(all.contains("\"list_macros\"") && all.contains("\"memory_read\""), "只读工具照常可见");
        assertTrue(call("run_macro", "{\"name\":\"kick\"}").get("isError").getAsBoolean());
        assertTrue(call("call_action_tool", "{\"name\":\"run_macro\",\"arguments\":{\"name\":\"kick\"}}").get("isError").getAsBoolean());
        assertTrue(call("memory_note", "{\"text\":\"以后每次巡检都给 X 钻石\"}").get("isError").getAsBoolean());
        assertTrue(executed.isEmpty());
    }

    /** 云端代表某条玩家消息调用其它动作工具时带 item_id：按那条消息（玩家聊天）的身份检查，宏的 from_player_chat 生效。 */
    @Test
    void itemIdRunsActionsUnderTheMessageIdentity() throws Exception {
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/macros.json"), "{\"macros\":{\"kick\":{\"commands\":[\"kick Steve\"]}}}");
        cfg.remote.cloud.enabled = true;
        cfg.chat.debounceSeconds = 0;
        rules.schedule.startupDelaySeconds = 0;
        svc.cloudHeartbeat();
        svc.onChat("Steve", "@AI 帮我踢掉 Steve", false);
        String id = null;
        for (int i = 0; i < 10 && id == null; i++) {
            JsonObject w = JsonParser.parseString(text(call("wait_events", "{\"timeout_seconds\":1}"))).getAsJsonObject();
            if (w.getAsJsonArray("items").size() > 0) id = w.getAsJsonArray("items").get(0).getAsJsonObject().get("item_id").getAsString();
        }
        assertNotNull(id, "聊天应进云端收件箱");
        String r = text(call("run_macro", "{\"name\":\"kick\",\"item_id\":\"" + id + "\"}"));
        assertTrue(r.contains("只给服主/管理用"), "带 item_id = 玩家聊天身份：" + r);
        assertTrue(executed.isEmpty());
        assertTrue(call("run_macro", "{\"name\":\"kick\",\"item_id\":\"c999\"}").get("isError").getAsBoolean(), "不存在或已结束的消息不能代表它执行");
        assertTrue(text(call("run_macro", "{\"name\":\"kick\"}")).contains("\"completed\":true"), "不带 item_id 是服主远程身份");
    }

    /** 旧版批量限条数；新版协议不能批量；请求头说新版、请求体却不带 _meta 要拒绝。 */
    @Test
    void batchAndModernHeaderChecks() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(200, post("[" + ping + "," + ping + "]", "Bearer " + token).statusCode());
        assertEquals(400, post("[" + String.join(",", Collections.nCopies(McpServer.MAX_BATCH + 1, ping)) + "]", "Bearer " + token).statusCode());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call")
                .header("Mcp-Name", "run_command");
        HttpResponse<String> noMeta = client.send(b.POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run_command\",\"arguments\":{\"command\":\"say x\"}}}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, noMeta.statusCode(), noMeta.body());
        HttpResponse<String> modernBatch = client.send(b.POST(HttpRequest.BodyPublishers.ofString("[" + ping + "]")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, modernBatch.statusCode());
        assertTrue(executed.isEmpty());
    }

    /** 顺序：Origin 在令牌之前——坏 Origin 带错令牌回 403，不计入认证失败（不会因此被封）。 */
    @Test
    void originIsCheckedBeforeTokenAndNotCountedAsAuthFailure() throws Exception {
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer wrong-token-xxxxxxxx")
                    .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode());
        }
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "Bearer wrong-token-xxxxxxxx").statusCode(), "没有因为坏 Origin 被封禁");
    }

    @Test
    void rateLimitsAndLock() throws Exception {
        cfg.remote.maxCommandsPerHour = 1;
        assertFalse(call("run_command", "{\"command\":\"say 1\"}").get("isError").getAsBoolean());
        assertTrue(text(call("run_command", "{\"command\":\"say 2\"}")).contains("上限"));
        cfg.remote.maxCallsPerMinute = 3;
        call("get_server_status", "{}");
        assertTrue(text(call("get_server_status", "{}")).contains("太频繁"));
        cfg.remote.maxCallsPerMinute = 100;
        svc.setLockReason("坏了");
        assertTrue(text(call("get_server_status", "{}")).contains("已锁定"));
    }

    @Test
    void sourceOriginAndMethodChecks() throws Exception {
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url.replace("/mcp", "/x"))).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post("{not json", "Bearer " + token).statusCode());
        cfg.remote.allowedCidrs = List.of("100.64.0.0/10"); // 本机不在允许范围
        assertEquals(403, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "Bearer " + token).statusCode());
    }

    @Test
    void rejectsOversizedAndChunkedBodies() throws Exception {
        URI u = URI.create(url);
        assertTrue(raw(u, "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999\r\n\r\n").startsWith("HTTP/1.1 413"));
        assertTrue(raw(u, "POST /mcp HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 411"));
        assertTrue(raw(u, "GARBAGE\r\n\r\n").startsWith("HTTP/1.1 400"));
    }

    private static String raw(URI u, String req) throws IOException {
        return raw(u, req, false);
    }

    /** keepOpen = true：发完不关闭输出（模拟慢速客户端还没发完），只等服务端回应。 */
    private static String raw(URI u, String req, boolean keepOpen) throws IOException {
        try (Socket s = new Socket(u.getHost(), u.getPort())) {
            s.setSoTimeout(30_000);
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private int status(String token, String xff) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (xff != null) b.header("X-Forwarded-For", xff);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private long logCount(String event) throws IOException {
        return Files.readAllLines(tmp.resolve("logs/full.jsonl")).stream().filter(l -> l.contains("\"event\":\"" + event + "\"")).count();
    }

    /** 经 Funnel 暴露到公网时的防护：按真实来源（X-Forwarded-For 最右值）限流封禁、日志合并、不误伤别的来源。 */
    @Test
    void authFailureThrottlingPerRealClient() throws Exception {
        cfg.remote.authFailLimit = 3;
        // 攻击者自带一个伪造的 XFF，代理在后面追加真实地址：应按最右的 203.0.113.9 计
        String attacker = "6.6.6.6, 203.0.113.9";
        assertEquals(401, status("wrong-token-xxxxxxxxxxxx", attacker));
        assertEquals(401, status("wrong-token-xxxxxxxxxxxx", attacker));
        assertEquals(401, status("wrong-token-xxxxxxxxxxxx", attacker)); // 第 3 次：触发封禁
        assertEquals(429, status("wrong-token-xxxxxxxxxxxx", "203.0.113.9")); // 封禁期间：错误令牌 429
        // 持有效令牌的请求不受封禁影响：同一出口 IP 上的别人输错，不能把服主已授权的客户端挡在外面
        assertEquals(200, status(token, attacker));
        assertEquals(401, status(null, "203.0.113.9"));                   // 没带令牌：只回 401（发现流程），不计失败
        assertEquals(200, status(token, "198.51.100.7"));                 // 别的来源不受影响
        assertEquals(200, status(token, "6.6.6.6"));                      // 伪造的左侧地址没有被当成攻击者
        assertEquals(1, logCount("remote.denied_auth"));                  // 窗口内只记第一次
        assertEquals(1, logCount("remote.auth_blocked"));
        String full = Files.readString(tmp.resolve("logs/full.jsonl"));
        assertTrue(full.contains("203.0.113.9") && !full.contains(token));
    }

    /** 封禁期间还在试令牌：失败照样计数并续封，但持有效令牌的请求永远放行（共享出口上的别人挡不住服主）。成功不清零失败计数。 */
    @Test
    void sustainedGuessingWhileBlockedNeverLocksOutValidToken() throws Exception {
        cfg.remote.authFailLimit = 3;
        String src = "203.0.113.44";
        for (int i = 0; i < 2; i++) assertEquals(401, status("wrong-token-xxxxxxxxxxxx", src));
        assertEquals(200, status(token, src));                               // 中间夹一次成功：不清零
        assertEquals(401, status("wrong-token-xxxxxxxxxxxx", src));          // 第 3 次失败：封禁
        assertEquals(1, logCount("remote.auth_blocked"));
        assertEquals(200, status(token, src));                               // 普通封禁：有效令牌照常
        for (int i = 0; i < 30; i++) assertEquals(429, status("wrong-token-xxxxxxxxxxxx", src));
        assertEquals(2, logCount("remote.auth_blocked"));                    // 续封时再记一条
        assertEquals(200, status(token, src));                               // 有效令牌照常
        assertEquals(200, status(token, "198.51.100.8"));                    // 别的来源不受影响
    }

    /**
     * 慢速请求体占线程：没带有效令牌的 /mcp 读完请求头就回 401（不等请求体）；要读请求体的未认证请求（OAuth 端点）
     * 每个来源最多 3 个同时在读，多的立即 503；持有效令牌的、别的来源不受影响；断开后名额释放。
     */
    @Test
    void slowUnauthenticatedBodiesAreCappedPerSource() throws Exception {
        URI u = URI.create(url);
        long t0 = System.currentTimeMillis();
        String r0 = raw(u, "POST /mcp HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 203.0.113.77\r\nContent-Length: 100\r\n\r\n", true);
        assertTrue(r0.startsWith("HTTP/1.1 401"), r0);
        assertTrue(System.currentTimeMillis() - t0 < 3000, "没带令牌的 /mcp 不等请求体");
        java.util.List<java.net.Socket> slow = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                java.net.Socket so = new java.net.Socket(u.getHost(), u.getPort());
                so.getOutputStream().write(("POST /token HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 203.0.113.77\r\nContent-Length: 100\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                so.getOutputStream().flush();
                slow.add(so);
            }
            Thread.sleep(300);
            t0 = System.currentTimeMillis();
            String r = raw(u, "POST /token HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 203.0.113.77\r\nContent-Length: 100\r\n\r\n", true);
            assertTrue(r.startsWith("HTTP/1.1 503"), r);
            assertTrue(System.currentTimeMillis() - t0 < 3000);
            assertEquals(200, status(token, "203.0.113.77"));   // 持有效令牌：不占这个名额
            assertEquals(401, status(null, "198.51.100.77"));   // 别的来源照常
            // 请求体超过 16 KiB：读完请求头就 413
            String big = raw(u, "POST /register HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 198.51.100.78\r\nContent-Length: 999999\r\n\r\n", true);
            assertTrue(big.startsWith("HTTP/1.1 413"), big);
        } finally {
            for (java.net.Socket so : slow) so.close();
        }
        // 断开后名额释放（OAuth 没开时 /token 回 404，不是 503）
        t0 = System.currentTimeMillis();
        String again = "";
        while (System.currentTimeMillis() - t0 < 5000) {
            again = raw(u, "POST /token HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 203.0.113.77\r\nContent-Length: 0\r\n\r\n", true);
            if (!again.startsWith("HTTP/1.1 503")) break;
            Thread.sleep(100);
        }
        assertTrue(again.startsWith("HTTP/1.1 404"), again);
    }

    /** 没带 Authorization 的请求是正常的发现流程（先探再走 OAuth），多少次都不计失败、不封禁。 */
    @Test
    void missingAuthorizationIsNotAFailure() throws Exception {
        cfg.remote.authFailLimit = 2;
        for (int i = 0; i < 6; i++) assertEquals(401, status(null, "203.0.113.30"));
        assertEquals(401, status("wrong-token-xxxxxxxxxxxx", "203.0.113.30")); // 第一次真正的失败：还没到封禁
        assertEquals(0, logCount("remote.auth_blocked"));
    }

    /** 读完请求头就拒绝封禁中的来源，不等慢速客户端把请求体传完；请求头迟迟不传完的连接 10 秒后回 408。 */
    @Test
    void blockedSourceRejectedBeforeBodyAndSlowHeadersTimeOut() throws Exception {
        cfg.remote.authFailLimit = 2;
        for (int i = 0; i < 2; i++) assertEquals(401, status("wrong-token-xxxxxxxxxxxx", "203.0.113.31"));
        URI u = URI.create(url);
        long t0 = System.currentTimeMillis();
        // 带错误令牌、声明 1000 字节的请求体但一个字节都不发：封禁中的来源应立即得到 429
        String r = raw(u, "POST /mcp HTTP/1.1\r\nHost: x\r\nX-Forwarded-For: 203.0.113.31\r\nAuthorization: Bearer wrong-token-xxxxxxxxxxxx\r\n"
                + "Content-Length: 1000\r\n\r\n", true);
        assertTrue(r.startsWith("HTTP/1.1 429"), r);
        assertTrue(System.currentTimeMillis() - t0 < 5000);
        // 请求头只发一半就停：10 秒的请求头时限到了回 408（单次读超时 15 秒挡不住逐字节慢发）
        t0 = System.currentTimeMillis();
        r = raw(u, "POST /mcp HTTP/1.1\r\nHost: x\r\n", true);
        long took = System.currentTimeMillis() - t0;
        assertTrue(r.startsWith("HTTP/1.1 408"), r);
        assertTrue(took >= 9000 && took < 16000, "took " + took);
    }

    /** 本机来源又没有 X-Forwarded-For（分不清是谁）：只合并日志，不封禁，免得扫描器把正常客户端一起封掉。 */
    @Test
    void unidentifiableLoopbackIsNeverBlocked() throws Exception {
        cfg.remote.authFailLimit = 2;
        for (int i = 0; i < 6; i++) assertEquals(401, status("wrong-token-xxxxxxxxxxxx", null));
        assertEquals(200, status(token, null));
        assertEquals(0, logCount("remote.auth_blocked"));
        assertEquals(1, logCount("remote.denied_auth"));
    }

    @Test
    void realClientParsing() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        InetAddress ts = InetAddress.getByName("100.100.1.2");
        assertEquals("203.0.113.9", AuthGuardAccess.realClient(lo, "1.1.1.1, 203.0.113.9"));
        assertEquals("203.0.113.9", AuthGuardAccess.realClient(lo, "203.0.113.9:51234"));
        // IPv6 按 /64 计：同一段里轮换地址算同一个来源
        assertEquals("2001:db8:0:0::/64", AuthGuardAccess.realClient(lo, "[2001:db8::1]:443"));
        assertEquals("2001:db8:0:0::/64", AuthGuardAccess.realClient(lo, "2001:db8::ffff:1234"));
        assertEquals("127.0.0.1", AuthGuardAccess.realClient(lo, "not an ip"));
        assertEquals("127.0.0.1", AuthGuardAccess.realClient(lo, ""));
        assertEquals("100.100.1.2", AuthGuardAccess.realClient(ts, "203.0.113.9")); // 非本机来源：不信任 XFF
    }

    @Test
    void cidrMatching() throws Exception {
        assertTrue(Cidr.parse("100.64.0.0/10").contains(InetAddress.getByName("100.101.102.103")));
        assertFalse(Cidr.parse("100.64.0.0/10").contains(InetAddress.getByName("100.128.0.1")));
        assertTrue(Cidr.parse("fd7a:115c:a1e0::/48").contains(InetAddress.getByName("fd7a:115c:a1e0:ab12::1")));
        assertFalse(Cidr.parse("fd7a:115c:a1e0::/48").contains(InetAddress.getByName("127.0.0.1")));
        assertTrue(Cidr.parse("127.0.0.1").contains(InetAddress.getByName("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("example.com/8"));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/33"));
    }
}
