package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.Activity;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.CloudInbox;
import io.github.aftsakee.aiadmin.agent.Prompts;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新版协议（2026-07-28，无握手）的请求头校验、server/discover，以及 MCP Events：订阅前验证回调、签名投递、重试/暂停、退订与持久化。
 * 走真实的本机 HTTP；回调接收端是测试里起的本机 HttpServer（用 -Daiadmin.devWebhookInsecure 放行 http + 本机地址）。
 */
class McpEventsTest {
    @TempDir
    Path tmp;
    BotConfig cfg;
    Rules rules;
    AgentService svc;
    McpServer mcp;
    String url;
    String token;
    HttpServer receiver;
    String callback;
    final HttpClient client = HttpClient.newHttpClient();
    final List<String> said = Collections.synchronizedList(new ArrayList<>());

    /** 接收端记录到的请求：路径、头、体。 */
    record Hit(String path, Map<String, String> headers, String body) {}

    final List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger eventStatus = new AtomicInteger(200);
    final AtomicInteger verifications = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("aiadmin.devWebhookInsecure", "true");
        cfg = new BotConfig();
        cfg.providers.get("deepseek").apiKey = "k";
        cfg.chat.debounceSeconds = 0;
        cfg.remote.enabled = true;
        cfg.remote.bind = "127.0.0.1";
        cfg.remote.port = 0;
        cfg.remote.cloud.enabled = true;
        cfg.remote.cloud.handoffSeconds = 2;
        cfg.remote.cloud.webhookHandoffSeconds = 5;
        rules = new Rules();
        rules.schedule.startupDelaySeconds = 0;
        JsonlLog log = new JsonlLog(tmp.resolve("logs"), null);
        Tools tools = new Tools();
        ActionTools.register(tools, new ActionTools.ServerActions() {
            @Override
            public CommandOutcome runCommand(String command, RunContext ctx) {
                return new CommandOutcome(true, 1, "ok");
            }

            @Override
            public String sendChat(String message, String target, RunContext ctx) {
                said.add(message);
                return null;
            }
        }, () -> cfg, () -> rules, log, new Activity());
        Prompts prompts = new Prompts(tmp.resolve("config"));
        prompts.ensure(cfg);
        svc = new AgentService(() -> cfg, () -> rules, new AgentService.Host() {
            public int onlinePlayers() {
                return 3;
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
        }, log, tmp.resolve("state"), prompts, null);
        svc.start();
        mcp = new McpServer(() -> cfg, () -> rules, tools, svc, log, "test");
        mcp.start(tmp.resolve("config"));
        long t0 = System.currentTimeMillis();
        while (mcp.url() == null && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        url = mcp.url();
        assertNotNull(url, mcp.describe());
        token = Files.readString(tmp.resolve("config/remote-token.txt")).trim();
        mcp.events().retryDelays(150, 150, 150);

        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
            String text = new String(body, StandardCharsets.UTF_8);
            hits.add(new Hit(ex.getRequestURI().getPath(), h, text));
            JsonObject j = JsonParser.parseString(text).getAsJsonObject();
            byte[] out;
            int status;
            if (j.has("type") && j.get("type").getAsString().equals("verification")) {
                verifications.incrementAndGet();
                out = ("{\"challenge\":\"" + j.get("challenge").getAsString() + "\"}").getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else {
                out = "{}".getBytes(StandardCharsets.UTF_8);
                status = eventStatus.get();
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        receiver.start();
        callback = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook/cb_1";
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("aiadmin.devWebhookInsecure");
        receiver.stop(0);
        mcp.stop();
        svc.stop();
    }

    // ———————————————————— 请求 ————————————————————

    private HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 新版请求：_meta 带协议版本/能力，请求头镜像 method 和 name；extra 里给 null 值 = 去掉这个头，给别的值 = 覆盖。 */
    private HttpResponse<String> modern(String method, String params, String name, String version, Map<String, String> extra) throws Exception {
        JsonObject p = JsonParser.parseString(params).getAsJsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty(McpProtocol.META_VERSION, version);
        JsonObject ci = new JsonObject();
        ci.addProperty("name", "TestClient");
        ci.addProperty("version", "9.9");
        meta.add(McpProtocol.META_CLIENT, ci);
        meta.add(McpProtocol.META_CAPS, new JsonObject());
        p.add("_meta", meta);
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("MCP-Protocol-Version", version);
        h.put("Mcp-Method", method);
        if (name != null) h.put("Mcp-Name", name);
        extra.forEach((k, v) -> {
            if (v == null) h.remove(k);
            else h.put(k, v);
        });
        return post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"" + method + "\",\"params\":" + p + "}", h);
    }

    private JsonObject modernOk(String method, String params, String name) throws Exception {
        HttpResponse<String> r = modern(method, params, name, McpProtocol.MODERN, Map.of());
        assertEquals(200, r.statusCode(), r.body());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        assertTrue(o.has("result"), r.body());
        return o.getAsJsonObject("result");
    }

    private JsonObject legacy(String method, String params) throws Exception {
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"" + method + "\",\"params\":" + params + "}", Map.of());
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static String newSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return "whsec_" + Base64.getEncoder().encodeToString(b);
    }

    private String subscribeParams(String secret, String kinds) {
        return "{\"name\":\"" + McpEvents.EVENT_ITEM + "\",\"arguments\":{" + (kinds == null ? "" : "\"kinds\":" + kinds)
                + "},\"delivery\":{\"mode\":\"webhook\",\"url\":\"" + callback + "\",\"secret\":\"" + secret + "\"},\"cursor\":null}";
    }

    private Hit waitHits(int n, long ms) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (hits.size() < n && System.currentTimeMillis() - t0 < ms) Thread.sleep(30);
        assertTrue(hits.size() >= n, "期望收到 " + n + " 个请求，实际 " + hits.size());
        return hits.get(n - 1);
    }

    private static CloudInbox.Item item(String who, String msg) {
        return new CloudInbox(System::currentTimeMillis).offer(Trigger.chat(List.of(who + "：" + msg), ""), who + "：" + msg + "\n请回应", 10);
    }

    // ———————————————————— 用例 ————————————————————

    /** 新版：discover 公布 events 能力和版本；结果带 resultType/serverInfo；请求头缺失或不一致 400；版本不支持 400 + 支持列表；未知方法 404；旧版不受影响。 */
    @Test
    void modernRequestsNeedMatchingHeadersAndLegacyStillWorks() throws Exception {
        JsonObject d = modernOk("server/discover", "{}", null);
        assertEquals("complete", d.get("resultType").getAsString());
        assertEquals(McpProtocol.MODERN, d.getAsJsonArray("supportedVersions").get(0).getAsString());
        assertTrue(d.getAsJsonObject("capabilities").has("events") && d.getAsJsonObject("capabilities").has("tools"), d.toString());
        assertEquals("aiadmin-minecraft", d.getAsJsonObject("_meta").getAsJsonObject(McpProtocol.META_SERVER).get("name").getAsString());
        assertTrue(d.get("instructions").getAsString().contains(McpEvents.EVENT_ITEM));

        JsonObject tl = modernOk("tools/list", "{}", null);
        assertEquals(60_000, tl.get("ttlMs").getAsInt());
        assertEquals("private", tl.get("cacheScope").getAsString());
        assertTrue(tl.getAsJsonArray("tools").size() > 10);

        JsonObject ev = modernOk("events/list", "{}", null);
        JsonObject e0 = ev.getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals(McpEvents.EVENT_ITEM, e0.get("name").getAsString());
        assertEquals("webhook", e0.getAsJsonArray("delivery").get(0).getAsString());
        assertTrue(e0.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("kinds"));
        assertTrue(e0.getAsJsonObject("payloadSchema").getAsJsonObject("properties").has("item_id"));

        JsonObject call = modernOk("tools/call", "{\"name\":\"aibot_status\",\"arguments\":{}}", "aibot_status");
        assertTrue(text(call).contains("云端值守"), text(call));
        // Mcp-Name 允许 base64 编码
        JsonObject call2 = modernOk("tools/call", "{\"name\":\"aibot_status\",\"arguments\":{}}",
                "=?base64?" + Base64.getEncoder().encodeToString("aibot_status".getBytes(StandardCharsets.UTF_8)) + "?=");
        assertTrue(text(call2).contains("云端值守"));

        HttpResponse<String> noHeader = modern("tools/list", "{}", null, McpProtocol.MODERN, Collections.singletonMap("MCP-Protocol-Version", null));
        assertEquals(400, noHeader.statusCode());
        assertEquals(-32020, JsonParser.parseString(noHeader.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        HttpResponse<String> badMethod = modern("tools/list", "{}", null, McpProtocol.MODERN, Map.of("Mcp-Method", "tools/call"));
        assertEquals(400, badMethod.statusCode());
        HttpResponse<String> badName = modern("tools/call", "{\"name\":\"aibot_status\",\"arguments\":{}}", "get_server_status", McpProtocol.MODERN, Map.of());
        assertEquals(400, badName.statusCode());
        assertEquals(-32020, JsonParser.parseString(badName.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        HttpResponse<String> badVersion = modern("tools/list", "{}", null, "1900-01-01", Map.of());
        assertEquals(400, badVersion.statusCode());
        JsonObject err = JsonParser.parseString(badVersion.body()).getAsJsonObject().getAsJsonObject("error");
        assertEquals(-32022, err.get("code").getAsInt());
        assertEquals(McpProtocol.MODERN, err.getAsJsonObject("data").getAsJsonArray("supported").get(0).getAsString());
        HttpResponse<String> unknown = modern("resources/list", "{}", null, McpProtocol.MODERN, Map.of());
        assertEquals(404, unknown.statusCode());
        assertEquals(-32601, JsonParser.parseString(unknown.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        HttpResponse<String> init = modern("initialize", "{\"protocolVersion\":\"2025-11-25\"}", null, McpProtocol.MODERN, Map.of());
        assertEquals(404, init.statusCode(), "新版没有 initialize");

        JsonObject li = legacy("initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}");
        assertEquals("2025-11-25", li.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertFalse(li.getAsJsonObject("result").getAsJsonObject("capabilities").has("events"), "旧版握手不公布 events（旧客户端用不上）");
        assertFalse(li.getAsJsonObject("result").has("resultType"));
        JsonObject lu = legacy("resources/list", "{}");
        assertEquals(-32601, lu.getAsJsonObject("error").get("code").getAsInt(), "旧版未知方法仍是 200 + 错误体");
    }

    /** 订阅：先发签名挑战、对方回显才建立；收件箱新消息推送到回调（签名可验、字段齐全）；推送算云端在线；送达后兜底放宽到 webhook_handoff。 */
    @Test
    void subscribeVerifiesCallbackThenPushesInboxItems() throws Exception {
        assertTrue(text(legacy("tools/call", "{\"name\":\"aibot_status\",\"arguments\":{}}").getAsJsonObject("result")).contains("离线"));
        String secret = newSecret();
        JsonObject sub = modernOk("events/subscribe", subscribeParams(secret, null), null);
        String subId = sub.get("id").getAsString();
        assertTrue(subId.startsWith("sub_"), subId);
        assertTrue(sub.get("refreshBefore").getAsString().startsWith("20"), sub.toString());
        assertTrue(sub.get("cursor").isJsonNull() && !sub.get("truncated").getAsBoolean());
        assertEquals(1, verifications.get());
        Hit v = hits.get(0);
        assertEquals("/hook/cb_1", v.path());
        assertTrue(v.headers().get("webhook-id").startsWith("msg_verification_"));
        assertEquals(subId, v.headers().get("x-mcp-subscription-id"));
        assertEquals(McpEvents.sign(McpEvents.secretBytes(secret), v.headers().get("webhook-id"), v.headers().get("webhook-timestamp"),
                v.body().getBytes(StandardCharsets.UTF_8)), v.headers().get("webhook-signature"), "挑战请求也按 Standard Webhooks 签名");

        String status = text(legacy("tools/call", "{\"name\":\"aibot_status\",\"arguments\":{}}").getAsJsonObject("result"));
        assertTrue(status.contains("在线（推送）") && status.contains("推送订阅 1 个"), status);
        assertTrue(mcp.describe().contains("推送订阅 1 个"), mcp.describe());

        // 玩家聊天 → 进收件箱 → 推送到回调
        long t0 = System.currentTimeMillis();
        assertTrue(svc.onChat("Steve", "@AI 帮我看看村庄", false));
        Hit e = waitHits(2, 5000);
        assertEquals("application/json", e.headers().get("content-type"));
        JsonObject body = JsonParser.parseString(e.body()).getAsJsonObject();
        assertEquals(McpEvents.EVENT_ITEM, body.get("name").getAsString());
        assertEquals(body.get("eventId").getAsString(), e.headers().get("webhook-id"));
        assertEquals(subId, e.headers().get("x-mcp-subscription-id"));
        assertEquals(McpEvents.sign(McpEvents.secretBytes(secret), e.headers().get("webhook-id"), e.headers().get("webhook-timestamp"),
                e.body().getBytes(StandardCharsets.UTF_8)), e.headers().get("webhook-signature"), "签名覆盖 id.timestamp.body");
        long ts = Long.parseLong(e.headers().get("webhook-timestamp"));
        assertTrue(Math.abs(ts * 1000 - System.currentTimeMillis()) < 10_000);
        JsonObject data = body.getAsJsonObject("data");
        assertEquals("chat", data.get("kind").getAsString());
        assertTrue(data.get("summary").getAsString().contains("Steve：@AI 帮我看看村庄"), data.toString());
        assertEquals(3, data.get("online_players").getAsInt());
        String itemId = data.get("item_id").getAsString();
        assertTrue(itemId.startsWith("c"));
        assertFalse(e.body().contains("\"instructions\""), "推送体只有数据摘要");

        // handoff 2 秒已过、webhook_handoff 5 秒未到：消息还在收件箱等云端来取（没交给本地）
        Thread.sleep(Math.max(0, 3000 - (System.currentTimeMillis() - t0)));
        JsonObject w = JsonParser.parseString(text(legacy("tools/call", "{\"name\":\"wait_events\",\"arguments\":{\"timeout_seconds\":0}}").getAsJsonObject("result")))
                .getAsJsonObject();
        assertEquals(1, w.getAsJsonArray("items").size(), "送达后兜底放宽：" + w);
        assertEquals(itemId, w.getAsJsonArray("items").get(0).getAsJsonObject().get("item_id").getAsString());
        assertEquals("已发送", text(legacy("tools/call", "{\"name\":\"reply_chat\",\"arguments\":{\"item_id\":\"" + itemId + "\",\"message\":\"看到了\"}}").getAsJsonObject("result")));
        assertEquals(List.of("看到了"), said);

        // 只订阅 patrol 的订阅收不到聊天
        hits.clear();
        String secret2 = newSecret();
        String other = callback.replace("cb_1", "cb_2");
        JsonObject sub2 = modernOk("events/subscribe", subscribeParams(secret2, "[\"patrol\"]").replace(callback, other), null);
        assertFalse(sub2.get("id").getAsString().equals(subId));
        waitHits(1, 3000); // 新地址的验证
        hits.clear();
        mcp.events().publish(item("Alex", "@AI 在吗"), 1);
        Hit only = waitHits(1, 3000);
        Thread.sleep(400);
        assertEquals(1, hits.size(), "只有订阅全部类型的那个收到");
        assertEquals("/hook/cb_1", only.path());
        assertTrue(mcp.eventSubscriptions().contains("类型 [patrol]"), mcp.eventSubscriptions());

        // 刷新：同一地址同一密钥不再发挑战；换密钥要重新验证，旧密钥 5 分钟内双签
        hits.clear();
        JsonObject again = modernOk("events/subscribe", subscribeParams(secret, null), null);
        assertEquals(subId, again.get("id").getAsString());
        assertEquals(2, verifications.get(), "刷新不重复验证（之前 cb_1、cb_2 各验证过一次）");
        String secret3 = newSecret();
        JsonObject rotated = modernOk("events/subscribe", subscribeParams(secret3, null), null);
        assertEquals(subId, rotated.get("id").getAsString());
        assertEquals(3, verifications.get(), "换密钥重新验证");
        hits.clear();
        mcp.events().publish(item("Bob", "@AI 你好"), 1);
        Hit r = waitHits(1, 3000);
        String[] sigs = r.headers().get("webhook-signature").split(" ");
        assertEquals(2, sigs.length, "轮换窗口内两把密钥都签");
        assertEquals(McpEvents.sign(McpEvents.secretBytes(secret3), r.headers().get("webhook-id"), r.headers().get("webhook-timestamp"), r.body().getBytes(StandardCharsets.UTF_8)), sigs[0]);
        assertEquals(McpEvents.sign(McpEvents.secretBytes(secret), r.headers().get("webhook-id"), r.headers().get("webhook-timestamp"), r.body().getBytes(StandardCharsets.UTF_8)), sigs[1]);
    }

    /** 投递失败：5xx 退避重试共 4 次后放弃；410 不重试；连续失败到上限暂停订阅（不再算在线），重新订阅恢复。退订后不再投递。 */
    @Test
    void deliveryRetriesGivesUpSuspendsAndUnsubscribeStops() throws Exception {
        String secret = newSecret();
        JsonObject sub = legacy("events/subscribe", subscribeParams(secret, null)).getAsJsonObject("result");
        String subId = sub.get("id").getAsString();
        hits.clear();
        eventStatus.set(503);
        mcp.events().publish(item("A", "1"), 1);
        Hit last = waitHits(4, 5000);
        Thread.sleep(500);
        assertEquals(4, hits.size(), "1 次 + 3 次重试");
        assertEquals(hits.get(0).headers().get("webhook-id"), last.headers().get("webhook-id"), "重试保留 eventId");
        for (Hit h : new ArrayList<>(hits)) { // 每次尝试都按当时的时间戳重新签名
            assertEquals(McpEvents.sign(McpEvents.secretBytes(secret), h.headers().get("webhook-id"), h.headers().get("webhook-timestamp"),
                    h.body().getBytes(StandardCharsets.UTF_8)), h.headers().get("webhook-signature"));
        }
        assertTrue(mcp.eventSubscriptions().contains("失败 1"), mcp.eventSubscriptions());

        hits.clear();
        eventStatus.set(410);
        mcp.events().publish(item("A", "2"), 1);
        waitHits(1, 3000);
        Thread.sleep(500);
        assertEquals(1, hits.size(), "410 不重试");
        for (int i = 0; i < McpEvents.SUSPEND_AFTER - 2; i++) {
            hits.clear();
            mcp.events().publish(item("A", "n" + i), 1);
            waitHits(1, 3000);
        }
        Thread.sleep(300);
        assertFalse(mcp.events().active(), "连续失败 → 暂停");
        assertTrue(mcp.eventSubscriptions().contains("已暂停"), mcp.eventSubscriptions());
        assertFalse(svc.status().contains("在线（推送）"), "暂停后不算在线：" + svc.status());
        hits.clear();
        mcp.events().publish(item("A", "x"), 1);
        Thread.sleep(400);
        assertTrue(hits.isEmpty(), "暂停的订阅不投递");

        eventStatus.set(200);
        legacy("events/subscribe", subscribeParams(secret, null));
        assertTrue(mcp.events().active(), "重新订阅恢复");
        assertTrue(svc.status().contains("在线（推送）"), svc.status());
        hits.clear();
        mcp.events().publish(item("A", "y"), 1);
        waitHits(1, 3000);

        JsonObject un = legacy("events/unsubscribe", "{\"name\":\"" + McpEvents.EVENT_ITEM + "\",\"arguments\":{},\"delivery\":{\"mode\":\"webhook\",\"url\":\"" + callback + "\"}}");
        assertEquals(0, un.getAsJsonObject("result").size());
        assertFalse(mcp.events().active());
        assertTrue(mcp.eventSubscriptions().contains("没有推送订阅"), mcp.eventSubscriptions());
        hits.clear();
        mcp.events().publish(item("A", "z"), 1);
        Thread.sleep(400);
        assertTrue(hits.isEmpty());
        assertEquals(0, legacy("events/unsubscribe", "{\"name\":\"" + McpEvents.EVENT_ITEM + "\",\"arguments\":{},\"delivery\":{\"mode\":\"webhook\",\"url\":\"" + callback + "\"}}")
                .getAsJsonObject("result").size(), "重复退订也成功");
        assertFalse(subId.isEmpty());
    }

    /** 参数与地址校验：密钥格式、未知事件/参数、非 https、内网/本机/Tailscale 段地址、主机白名单。 */
    @Test
    void secretUrlAndHostValidation() throws Exception {
        JsonObject bad = legacy("events/subscribe", subscribeParams("not-a-secret", null));
        assertEquals(-32602, bad.getAsJsonObject("error").get("code").getAsInt());
        JsonObject short1 = legacy("events/subscribe", subscribeParams("whsec_" + Base64.getEncoder().encodeToString(new byte[8]), null));
        assertEquals(-32602, short1.getAsJsonObject("error").get("code").getAsInt(), "密钥太短");
        JsonObject unknown = legacy("events/subscribe", subscribeParams(newSecret(), null).replace(McpEvents.EVENT_ITEM, "nope"));
        assertEquals(-32602, unknown.getAsJsonObject("error").get("code").getAsInt());
        JsonObject badArg = legacy("events/subscribe", subscribeParams(newSecret(), null).replace("\"arguments\":{}", "\"arguments\":{\"player\":\"x\"}"));
        assertEquals(-32602, badArg.getAsJsonObject("error").get("code").getAsInt(), "未知参数");
        JsonObject badKind = legacy("events/subscribe", subscribeParams(newSecret(), "[\"boss\"]"));
        assertEquals(-32602, badKind.getAsJsonObject("error").get("code").getAsInt());
        JsonObject noVerify = legacy("events/subscribe", subscribeParams(newSecret(), null).replace("/hook/cb_1", "/nowhere"));
        assertEquals(McpEvents.CALLBACK_ERROR, noVerify.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("status_404", noVerify.getAsJsonObject("error").getAsJsonObject("data").get("reason").getAsString());
        assertEquals(0, mcp.events().count());

        // 严格模式（线上）：只认 https + 公网地址
        McpEvents strict = new McpEvents(() -> cfg, new JsonlLog(tmp.resolve("logs2"), null), tmp.resolve("x.json"), p -> true, p -> p, false,
                (u, b, h) -> new McpEvents.Sender.Reply(200, new byte[0]));
        assertEquals("not_https", reason(() -> strict.checkUrl("http://chatgpt.com/cb")));
        assertEquals("private_address", reason(() -> strict.checkUrl("https://127.0.0.1/cb")));
        assertEquals("private_address", reason(() -> strict.checkUrl("https://10.1.2.3/cb")));
        assertEquals("private_address", reason(() -> strict.checkUrl("https://100.100.1.1/cb")));
        assertEquals("private_address", reason(() -> strict.checkUrl("https://[fd7a:115c:a1e0::1]/cb")));
        assertEquals("private_address", reason(() -> strict.checkUrl("https://localhost/cb")));
        assertEquals("invalid_url", reason(() -> strict.checkUrl("https://user@1.1.1.1/cb")));
        assertEquals("1.1.1.1", strict.checkUrl("https://1.1.1.1/cb").getHost());
        cfg.remote.cloud.webhookHosts = List.of("chatgpt.com", "*.openai.com");
        assertEquals("host_not_allowed", reason(() -> strict.checkUrl("https://1.1.1.1/cb")));
        assertTrue(McpEvents.hostAllowed("events.openai.com", cfg.remote.cloud.webhookHosts));
        assertTrue(McpEvents.hostAllowed("ChatGPT.com", cfg.remote.cloud.webhookHosts));
        assertFalse(McpEvents.hostAllowed("openai.com", cfg.remote.cloud.webhookHosts));
        assertFalse(McpEvents.hostAllowed("evil-chatgpt.com", cfg.remote.cloud.webhookHosts));
        assertTrue(McpEvents.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(McpEvents.publicAddress(InetAddress.getByName("169.254.1.1")));
        assertFalse(McpEvents.publicAddress(InetAddress.getByName("192.168.1.1")));
        assertFalse(McpEvents.publicAddress(InetAddress.getByName("172.16.5.5")));
        assertFalse(McpEvents.publicAddress(InetAddress.getByName("100.127.255.255")));
        assertTrue(McpEvents.publicAddress(InetAddress.getByName("100.128.0.1")));
        assertTrue(McpEvents.publicAddress(InetAddress.getByName("2606:4700::1111")));
        assertFalse(McpEvents.publicAddress(InetAddress.getByName("fc00::1")));
        // 内嵌 IPv4 的过渡地址：NAT64（可通到 169.254.169.254）、IPv4 兼容、6to4、Teredo
        for (String a : java.util.List.of("64:ff9b::a9fe:a9fe", "::a9fe:a9fe", "2002:a9fe:a9fe::1", "2001:0:4136:e378::1", "::")) {
            assertFalse(McpEvents.publicAddress(InetAddress.getByName(a)), a);
        }
    }

    private static String reason(Runnable r) {
        McpEvents.RpcError e = assertThrows(McpEvents.RpcError.class, r::run);
        return e.data.get("reason").getAsString();
    }

    /** 订阅持久化：重启后仍在（密钥能签、到期的丢掉）；吊销 principal 删除订阅；文件里没有别的敏感信息。 */
    @Test
    void subscriptionsSurviveRestartAndFollowPrincipal() throws Exception {
        String secret = newSecret();
        String subId = legacy("events/subscribe", subscribeParams(secret, "[\"chat\",\"event\"]")).getAsJsonObject("result").get("id").getAsString();
        Path f = tmp.resolve("config/mcp-events.json");
        assertTrue(Files.exists(f));
        JsonObject saved = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertEquals(1, saved.get("schema_version").getAsInt());
        JsonObject s0 = saved.getAsJsonArray("subscriptions").get(0).getAsJsonObject();
        assertEquals("token", s0.get("principal").getAsString());
        assertEquals(secret, s0.get("secret").getAsString());
        assertEquals(2, s0.getAsJsonObject("arguments").getAsJsonArray("kinds").size());

        McpEvents reloaded = new McpEvents(() -> cfg, new JsonlLog(tmp.resolve("logs3"), null), f, p -> true, p -> p, true,
                (u, b, h) -> new McpEvents.Sender.Reply(200, new byte[0]));
        assertEquals(1, reloaded.count());
        assertTrue(reloaded.active());
        assertTrue(reloaded.describe().contains(subId) && reloaded.describe().contains("[chat, event]"), reloaded.describe());
        reloaded.removeByPrincipal("token");
        assertEquals(0, reloaded.count());
        assertEquals(0, new McpEvents(() -> cfg, new JsonlLog(tmp.resolve("logs4"), null), f, p -> true, p -> p, true,
                (u, b, h) -> new McpEvents.Sender.Reply(200, new byte[0])).count(), "删除也落盘");

        // 到期的订阅重启后不再加载
        String json = Files.readString(f);
        legacy("events/subscribe", subscribeParams(secret, null));
        String now = Files.readString(f).replaceAll("\"expires\":\\d+", "\"expires\":1");
        Files.writeString(f, now);
        assertEquals(0, new McpEvents(() -> cfg, new JsonlLog(tmp.resolve("logs5"), null), f, p -> true, p -> p, true,
                (u, b, h) -> new McpEvents.Sender.Reply(200, new byte[0])).count());
        assertFalse(json.contains(token), "订阅文件里没有远程令牌");
    }
}
