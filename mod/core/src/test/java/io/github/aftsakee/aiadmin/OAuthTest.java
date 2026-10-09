package io.github.aftsakee.aiadmin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.remote.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远程接口的 OAuth：发现、动态注册、批准页、PKCE、令牌、刷新轮换、吊销、失败计数，以及各条安全边界。走真实的本机 HTTP。 */
class OAuthTest {
    static final String CHATGPT = "https://chatgpt.com/connector_platform_oauth_redirect";
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk_extra";

    @TempDir
    Path tmp;
    TestRig rig;
    BotConfig cfg;
    Rules rules;
    AgentService svc;
    McpServer mcp;
    Tools tools;
    JsonlLog log;
    String base;
    String token;
    final HttpClient http = HttpClient.newHttpClient(); // 默认不跟随跳转：要看 302 的 Location

    @BeforeEach
    void setUp() throws Exception {
        rig = new TestRig(tmp, false);
        rig.ensurePrompts = false;
        cfg = rig.cfg;
        rules = rig.rules;
        log = rig.log;
        tools = rig.tools;
        cfg.remote.enabled = true;
        cfg.remote.bind = "127.0.0.1";
        cfg.remote.port = 0;
        rig.host.online = 0;
        tools.add(Tools.schema("get_server_status", "状态").build(), (a, c) -> "{\"tps\":20}");
        svc = rig.start();
        startMcp();
        token = Files.readString(tmp.resolve("config/remote-token.txt")).trim();
    }

    private void startMcp() throws Exception {
        mcp = new McpServer(() -> cfg, () -> rules, tools, svc, log, "test");
        mcp.start(tmp.resolve("config"));
        long t0 = System.currentTimeMillis();
        while (mcp.url() == null && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        assertNotNull(mcp.url(), mcp.describe());
        URI u = URI.create(mcp.url());
        cfg.remote.port = u.getPort(); // 重启时用同一个端口
        base = "http://127.0.0.1:" + u.getPort();
        cfg.remote.publicBaseUrl = base; // 测试用本机 http（正式环境必须 https，由 ConfigStore 校验）
    }

    @AfterEach
    void tearDown() {
        mcp.stop();
        rig.close();
    }

    // ———————————————————— HTTP 小工具 ————————————————————

    private HttpResponse<String> send(String method, String path, String body, String contentType, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null, Map.of());
    }

    private HttpResponse<String> form(String path, Map<String, String> f, Map<String, String> headers) throws Exception {
        StringBuilder sb = new StringBuilder();
        f.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        return send("POST", path, sb.toString(), "application/x-www-form-urlencoded", headers);
    }

    private HttpResponse<String> mcpCall(String bearer) throws Exception {
        return send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", "application/json",
                bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer));
    }

    private JsonObject register(String redirect, String method, Map<String, String> headers) throws Exception {
        HttpResponse<String> r = send("POST", "/register", "{\"client_name\":\"ChatGPT <b>x</b>\",\"redirect_uris\":[\"" + redirect + "\"],"
                + "\"token_endpoint_auth_method\":\"" + method + "\",\"grant_types\":[\"authorization_code\",\"refresh_token\"]}", "application/json", headers);
        assertEquals(201, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String authorizeQuery(String clientId, String redirect, String method) throws Exception {
        return "/authorize?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect) + "&code_challenge=" + challenge(VERIFIER)
                + "&code_challenge_method=" + method + "&state=xyz%20123&scope=mcp&resource=" + enc(base + "/mcp");
    }

    private static String confirmNo(String html) {
        Matcher m = Pattern.compile("class=\"code\">([A-Z2-9]{6})<").matcher(html);
        assertTrue(m.find(), html);
        return m.group(1);
    }

    private static String requestId(String html) {
        Matcher m = Pattern.compile("name=\"request\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), html);
        return m.group(1);
    }

    private static Map<String, String> query(String location) {
        Map<String, String> m = new HashMap<>();
        for (String kv : URI.create(location).getRawQuery().split("&")) {
            int eq = kv.indexOf('=');
            m.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** 走完注册 → 授权页 → 批准 → 换令牌，返回 /token 的响应。 */
    private JsonObject fullFlow(String approval) throws Exception {
        JsonObject client = register(CHATGPT, "none", Map.of());
        String cid = client.get("client_id").getAsString();
        String id = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body());
        HttpResponse<String> ok = form("/authorize", Map.of("request", id, "approval", approval, "decision", "approve"), Map.of("Origin", base));
        assertEquals(302, ok.statusCode(), ok.body());
        Map<String, String> q = query(ok.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> t = form("/token", Map.of("grant_type", "authorization_code", "code", q.get("code"), "redirect_uri", CHATGPT,
                "code_verifier", VERIFIER, "client_id", cid), Map.of());
        assertEquals(200, t.statusCode(), t.body());
        JsonObject o = JsonParser.parseString(t.body()).getAsJsonObject();
        o.addProperty("client_id", cid);
        return o;
    }

    private long logCount(String event) throws Exception {
        return Files.readAllLines(tmp.resolve("logs/full.jsonl")).stream().filter(l -> l.contains("\"event\":\"" + event + "\"")).count();
    }

    // ———————————————————— 测试 ————————————————————

    @Test
    void discoveryMetadataAnd401Hint() throws Exception {
        JsonObject prm = JsonParser.parseString(get("/.well-known/oauth-protected-resource").body()).getAsJsonObject();
        assertEquals(base + "/mcp", prm.get("resource").getAsString());
        assertEquals(base, prm.getAsJsonArray("authorization_servers").get(0).getAsString());
        assertEquals(200, get("/.well-known/oauth-protected-resource/mcp").statusCode());
        JsonObject as = JsonParser.parseString(get("/.well-known/oauth-authorization-server").body()).getAsJsonObject();
        assertEquals(base, as.get("issuer").getAsString());
        assertEquals(base + "/token", as.get("token_endpoint").getAsString());
        assertEquals("[\"S256\"]", as.getAsJsonArray("code_challenge_methods_supported").toString());
        HttpResponse<String> r = mcpCall(null);
        assertEquals(401, r.statusCode());
        assertEquals("Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource\"", r.headers().firstValue("WWW-Authenticate").orElse(""));
        // 只有 /mcp 和 OAuth 的几个路径，其他一律 404
        assertEquals(404, get("/.well-known/openid-configuration").statusCode());
        assertEquals(404, get("/").statusCode());
        assertEquals(404, get("/authorize/x").statusCode());
        assertEquals(200, mcpCall(token).statusCode()); // 固定令牌照常可用
    }

    @Test
    void withoutPublicBaseUrlOAuthIsOff() throws Exception {
        cfg.remote.publicBaseUrl = "";
        assertEquals(404, send("POST", "/register", "{}", "application/json", Map.of()).statusCode());
        assertEquals(404, get("/.well-known/oauth-protected-resource").statusCode());
        assertEquals("Bearer", mcpCall(null).headers().firstValue("WWW-Authenticate").orElse(""));
        assertNull(mcp.pendingRequests());
        assertNull(mcp.approve("ABCDEF"));
    }

    @Test
    void fullFlowRefreshRotationAndReuseDetection() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        HttpResponse<String> page = get(authorizeQuery(cid, CHATGPT, "S256"));
        assertEquals(200, page.statusCode());
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
        // no-referrer 会让浏览器提交表单时带 Origin: null；批准页必须是 same-origin
        assertEquals("same-origin", page.headers().firstValue("Referrer-Policy").orElse(""));
        String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("frame-ancestors 'none'") && csp.contains("form-action 'self' https://chatgpt.com"), csp);
        assertTrue(page.body().contains("ChatGPT &lt;b&gt;x&lt;/b&gt;") && page.body().contains("chatgpt.com"), "客户端名要转义，并显示跳转目标");
        String id = requestId(page.body());
        // 口令错误：不跳转，回到批准页并计一次失败
        HttpResponse<String> bad = form("/authorize", Map.of("request", id, "approval", "wrong", "decision", "approve"), Map.of("Origin", base));
        assertEquals(401, bad.statusCode());
        assertTrue(bad.headers().firstValue("Location").isEmpty());
        assertEquals(1, logCount("remote.denied_auth"));
        assertEquals(403, form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", "null")).statusCode(),
                "Origin 校验不放松：null 仍然拒绝");
        HttpResponse<String> ok = form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base));
        assertEquals(302, ok.statusCode());
        String loc = ok.headers().firstValue("Location").orElseThrow();
        assertTrue(loc.startsWith(CHATGPT + "?code="), loc);
        Map<String, String> q = query(loc);
        assertEquals("xyz 123", q.get("state"));
        assertEquals(base, q.get("iss"));
        // 请求号只能用一次
        assertEquals(400, form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base)).statusCode());
        HttpResponse<String> t = form("/token", Map.of("grant_type", "authorization_code", "code", q.get("code"), "redirect_uri", CHATGPT,
                "code_verifier", VERIFIER, "client_id", cid), Map.of());
        assertEquals(200, t.statusCode(), t.body());
        assertEquals("no-store", t.headers().firstValue("Cache-Control").orElse(""));
        JsonObject tok = JsonParser.parseString(t.body()).getAsJsonObject();
        String at = tok.get("access_token").getAsString(), rt = tok.get("refresh_token").getAsString();
        assertEquals(3600, tok.get("expires_in").getAsInt());
        assertEquals(200, mcpCall(at).statusCode());
        // 授权码只能换一次
        assertEquals(400, form("/token", Map.of("grant_type", "authorization_code", "code", q.get("code"), "redirect_uri", CHATGPT,
                "code_verifier", VERIFIER, "client_id", cid), Map.of()).statusCode());
        // 刷新：换新的一对，旧的 refresh token 作废
        HttpResponse<String> r1 = form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rt, "client_id", cid), Map.of());
        assertEquals(200, r1.statusCode(), r1.body());
        JsonObject tok2 = JsonParser.parseString(r1.body()).getAsJsonObject();
        String at2 = tok2.get("access_token").getAsString();
        assertFalse(rt.equals(tok2.get("refresh_token").getAsString()));
        assertEquals(200, mcpCall(at2).statusCode());
        // 再正常轮换一次（tok2 → tok3），这时旧的 rt 再出现就不是"响应丢了的重试"（它换出来的 tok2 已经被用掉）：判定被盗，吊销全部令牌
        JsonObject tok3 = JsonParser.parseString(form("/token", Map.of("grant_type", "refresh_token", "refresh_token",
                tok2.get("refresh_token").getAsString(), "client_id", cid), Map.of()).body()).getAsJsonObject();
        String at3 = tok3.get("access_token").getAsString();
        assertEquals(200, mcpCall(at3).statusCode());
        assertEquals(400, form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rt, "client_id", cid), Map.of()).statusCode());
        assertEquals(1, logCount("oauth.refresh_reuse"));
        assertEquals(401, mcpCall(at3).statusCode());
        assertEquals(400, form("/token", Map.of("grant_type", "refresh_token", "refresh_token", tok3.get("refresh_token").getAsString(),
                "client_id", cid), Map.of()).statusCode());
        // 日志里只有 client_id 和来源，没有任何令牌、授权码
        String full = Files.readString(tmp.resolve("logs/full.jsonl"));
        for (String secret : new String[]{at, rt, at2, q.get("code"), token}) assertFalse(full.contains(secret));
        assertTrue(full.contains(cid));
    }

    @Test
    void auditLogNamesOAuthClientOnToolCalls() throws Exception {
        JsonObject t = fullFlow(token);
        HttpResponse<String> r = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_server_status\"}}",
                "application/json", Map.of("Authorization", "Bearer " + t.get("access_token").getAsString()));
        assertEquals(200, r.statusCode());
        String full = Files.readString(tmp.resolve("logs/full.jsonl"));
        String cid = t.get("client_id").getAsString();
        assertTrue(full.lines().anyMatch(l -> l.contains("\"event\":\"remote.call\"") && l.contains("oauth:") && l.contains(cid)), full);
    }

    @Test
    void codeBoundToVerifierRedirectAndClient() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        String other = register("https://claude.ai/api/mcp/auth_callback", "none", Map.of()).get("client_id").getAsString();
        for (String[] bad : new String[][]{
                {"code_verifier", "x".repeat(43)},             // 校验值不对
                {"redirect_uri", CHATGPT + "/extra"},           // 回调地址不一致
                {"client_id", other}}) {                        // 换了客户端
            String id = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body());
            String code = query(form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base))
                    .headers().firstValue("Location").orElseThrow()).get("code");
            Map<String, String> f = new HashMap<>(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", CHATGPT,
                    "code_verifier", VERIFIER, "client_id", cid));
            f.put(bad[0], bad[1]);
            assertEquals(400, form("/token", f, Map.of()).statusCode(), bad[0]);
            // 失败一次后授权码就作废了，正确参数也换不到
            f = Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", CHATGPT, "code_verifier", VERIFIER, "client_id", cid);
            assertEquals(400, form("/token", f, Map.of()).statusCode(), bad[0]);
        }
    }

    @Test
    void authorizeRejectsBadClientRedirectPkceAndOrigin() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        // 不认识的客户端、回调地址只差一点（前缀相同）：只显示错误页，绝不跳转
        HttpResponse<String> r = get(authorizeQuery("mc-nobody", CHATGPT, "S256"));
        assertEquals(400, r.statusCode());
        assertTrue(r.headers().firstValue("Location").isEmpty());
        r = get(authorizeQuery(cid, CHATGPT + "x", "S256"));
        assertEquals(400, r.statusCode());
        assertTrue(r.headers().firstValue("Location").isEmpty());
        // PKCE plain：跳回客户端报 invalid_request
        r = get(authorizeQuery(cid, CHATGPT, "plain"));
        assertEquals(302, r.statusCode());
        assertEquals("invalid_request", query(r.headers().firstValue("Location").orElseThrow()).get("error"));
        // resource 指向别处
        r = get(authorizeQuery(cid, CHATGPT, "S256").replace(enc(base + "/mcp"), enc("https://evil.example/mcp")));
        assertEquals("invalid_target", query(r.headers().firstValue("Location").orElseThrow()).get("error"));
        // 别的网站代为提交批准表单（Origin 不是本站）
        String id = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body());
        assertEquals(403, form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", "https://evil.example")).statusCode());
        // 拒绝：跳回 access_denied，带 state
        HttpResponse<String> d = form("/authorize", Map.of("request", id, "decision", "deny"), Map.of("Origin", base));
        Map<String, String> q = query(d.headers().firstValue("Location").orElseThrow());
        assertEquals("access_denied", q.get("error"));
        assertEquals("xyz 123", q.get("state"));
    }

    @Test
    void registrationValidationAndRateLimits() throws Exception {
        String[] bad = {"http://chatgpt.com/cb", "https://evil.example/cb", "https://chatgpt.com/cb#frag", "https://user@chatgpt.com/cb", "not a uri"};
        for (String u : bad) {
            HttpResponse<String> r = send("POST", "/register", "{\"redirect_uris\":[\"" + u + "\"]}", "application/json",
                    Map.of("X-Forwarded-For", "198.51.100.1"));
            assertEquals(400, r.statusCode(), u);
        }
        assertEquals(400, send("POST", "/register", "{\"redirect_uris\":[\"" + CHATGPT + "\"],\"token_endpoint_auth_method\":\"private_key_jwt\"}",
                "application/json", Map.of()).statusCode());
        assertEquals(201, send("POST", "/register", "{\"redirect_uris\":[\"http://127.0.0.1:33418/callback\"]}", "application/json", Map.of()).statusCode());
        // redirect_uris 先查个数（最多 5 个），请求体最大 16 KiB
        StringBuilder six = new StringBuilder();
        for (int i = 0; i < 6; i++) six.append(i == 0 ? "" : ",").append("\"").append(CHATGPT).append(i).append("\"");
        assertEquals(400, send("POST", "/register", "{\"redirect_uris\":[" + six + "]}", "application/json", Map.of()).statusCode());
        assertEquals(413, send("POST", "/register", "{\"client_name\":\"" + "x".repeat(20_000) + "\",\"redirect_uris\":[\"" + CHATGPT + "\"]}",
                "application/json", Map.of()).statusCode());
        // 每个来源每 10 分钟 10 次；别的来源不受影响（没有全服共享的上限）
        Map<String, String> src = Map.of("X-Forwarded-For", "203.0.113.50");
        for (int i = 0; i < 10; i++) register(CHATGPT, "none", src);
        assertEquals(429, send("POST", "/register", "{\"redirect_uris\":[\"" + CHATGPT + "\"]}", "application/json", src).statusCode());
        register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.51"));
        // 白名单清空 = 不限制主机（但仍要 https）
        cfg.remote.oauth.allowedRedirectHosts.clear();
        assertEquals(201, send("POST", "/register", "{\"redirect_uris\":[\"https://other.example/cb\"]}", "application/json",
                Map.of("X-Forwarded-For", "203.0.113.53")).statusCode());
    }
    /** 存储压力只来自没被批准的客户端：满了不清理 10 分钟内的新客户端；已批准客户端的上限在批准时检查。 */
    @Test
    void unusedAndAuthorizedClientLimits() throws Exception {
        cfg.remote.oauth.maxUnusedClients = 2;
        cfg.remote.oauth.maxClients = 1;
        fullFlow(token);                                              // 已批准（不占未用名额）
        register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.60"));
        String cid = register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.61")).get("client_id").getAsString();
        HttpResponse<String> r = send("POST", "/register", "{\"redirect_uris\":[\"" + CHATGPT + "\"]}", "application/json",
                Map.of("X-Forwarded-For", "203.0.113.62"));
        assertEquals(429, r.statusCode()); // 两个未用的都不到 10 分钟，不清理
        // 已批准的客户端已有 1 个（上限 1）：再批准一个新的会被拒绝
        String id = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body());
        HttpResponse<String> full = form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base));
        assertEquals(409, full.statusCode());
        assertTrue(full.body().contains("上限"));
    }
    /** 未用客户端满了：从注册最多的来源里淘汰（多个来源轮流灌注册，服主自己的那个不会先被挤掉）。 */
    @Test
    void unusedClientEvictionTargetsTheBusiestSource() throws Exception {
        cfg.remote.oauth.maxUnusedClients = 3;
        String mine = register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.70")).get("client_id").getAsString();
        String first = register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.71")).get("client_id").getAsString();
        register(CHATGPT, "none", Map.of("X-Forwarded-For", "203.0.113.71"));
        HttpResponse<String> r = send("POST", "/register", "{\"redirect_uris\":[\"" + CHATGPT + "\"]}", "application/json",
                Map.of("X-Forwarded-For", "203.0.113.71"));
        assertEquals(201, r.statusCode(), r.body());
        assertEquals(200, get(authorizeQuery(mine, CHATGPT, "S256")).statusCode(), "服主那个来源的注册还在");
        assertEquals(400, get(authorizeQuery(first, CHATGPT, "S256")).statusCode(), "被淘汰的是注册最多的来源最早的那个");
    }

    @Test
    void tokenFailuresCountTowardBlocking() throws Exception {
        cfg.remote.authFailLimit = 3;
        Map<String, String> src = Map.of("X-Forwarded-For", "203.0.113.70");
        for (int i = 0; i < 3; i++) {
            assertEquals(401, form("/token", Map.of("grant_type", "authorization_code", "code", "x", "client_id", "mc-nobody"), src).statusCode());
        }
        assertEquals(1, logCount("remote.auth_blocked"));
        // 封禁中：失败的请求 429；公开的元数据照常；批准表单直接 429
        assertEquals(429, form("/token", Map.of("grant_type", "authorization_code", "code", "x", "client_id", "mc-nobody"), src).statusCode());
        assertEquals(429, send("POST", "/mcp", "{}", "application/json", Map.of("X-Forwarded-For", "203.0.113.70", "Authorization", "Bearer nope-nope-nope")).statusCode());
        assertEquals(200, send("GET", "/.well-known/oauth-authorization-server", null, null, src).statusCode());
        assertEquals(429, form("/authorize", Map.of("request", "x", "approval", token), Map.of("X-Forwarded-For", "203.0.113.70", "Origin", base)).statusCode());
    }

    /** 共享出口 IP 被别人弄到封禁时，持有效固定令牌、OAuth access token、授权码、refresh token 的请求照常成功。 */
    @Test
    void validCredentialsBypassBlock() throws Exception {
        JsonObject t = fullFlow(token); // 先在 127.0.0.1（无 XFF）完成授权
        String cid = t.get("client_id").getAsString();
        String shared = "203.0.113.90";
        // 同一出口的"别人"刷错 5 次
        for (int i = 0; i < 5; i++) {
            send("POST", "/mcp", "{}", "application/json", Map.of("X-Forwarded-For", shared, "Authorization", "Bearer wrong-wrong-wrong"));
        }
        Map<String, String> via = Map.of("X-Forwarded-For", shared);
        assertEquals(429, send("POST", "/mcp", "{}", "application/json", Map.of("X-Forwarded-For", shared, "Authorization", "Bearer wrong-wrong-wrong")).statusCode());
        assertEquals(200, send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "application/json",
                Map.of("X-Forwarded-For", shared, "Authorization", "Bearer " + t.get("access_token").getAsString())).statusCode());
        assertEquals(200, send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "application/json",
                Map.of("X-Forwarded-For", shared, "Authorization", "Bearer " + token)).statusCode());
        HttpResponse<String> r = form("/token", Map.of("grant_type", "refresh_token", "refresh_token", t.get("refresh_token").getAsString(),
                "client_id", cid), via);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(201, send("POST", "/register", "{\"redirect_uris\":[\"" + CHATGPT + "\"]}", "application/json", via).statusCode());
    }

    /**
     * refresh：30 秒内用同一个 refresh 再来（网络重试，或多进程连接器并发刷新）原样拿到同一对令牌，两边都能用、谁都不被踢；
     * 旧 refresh 过了宽限期又出现判定被盗、全部吊销；旧 refresh 的记录重启后还在；超过首次授权 + refresh_max_days 必须重新批准。
     */
    @Test
    void refreshRetryGraceRotationPersistenceAndAbsoluteLifetime() throws Exception {
        JsonObject t = fullFlow(token);
        String cid = t.get("client_id").getAsString(), rt0 = t.get("refresh_token").getAsString();
        JsonObject t1 = JsonParser.parseString(form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rt0, "client_id", cid), Map.of()).body())
                .getAsJsonObject();
        // 另一个进程几乎同时也拿 rt0 刷新（或响应丢了在重试）：拿到同一对，先到的那个进程手里的令牌照样有效
        HttpResponse<String> retry = form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rt0, "client_id", cid), Map.of());
        assertEquals(200, retry.statusCode(), retry.body());
        JsonObject t2 = JsonParser.parseString(retry.body()).getAsJsonObject();
        assertEquals(t1.get("access_token").getAsString(), t2.get("access_token").getAsString(), "宽限期内原样重放");
        assertEquals(t1.get("refresh_token").getAsString(), t2.get("refresh_token").getAsString());
        assertEquals(0, logCount("oauth.refresh_reuse"));
        assertEquals(1, logCount("oauth.refresh_replay"));
        assertEquals(200, mcpCall(t1.get("access_token").getAsString()).statusCode(), "先到的那对没被作废");
        // 正常往下轮换：t1 的 refresh 换出 t3；之后 t1 的 refresh 在宽限期内再来，同样原样重放 t3
        JsonObject t3 = JsonParser.parseString(form("/token", Map.of("grant_type", "refresh_token", "refresh_token", t1.get("refresh_token").getAsString(),
                "client_id", cid), Map.of()).body()).getAsJsonObject();
        assertEquals(200, mcpCall(t3.get("access_token").getAsString()).statusCode());
        assertEquals(0, logCount("oauth.refresh_reuse"));
        // 旧 refresh 的记录重启后还在：新授权一次、换掉，把"换掉的时间"改到 60 秒前、重启：旧的再出现 → 判定被盗，吊销
        JsonObject v = fullFlow(token);
        String cid2 = v.get("client_id").getAsString(), rv0 = v.get("refresh_token").getAsString();
        JsonObject v1 = JsonParser.parseString(form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rv0, "client_id", cid2), Map.of()).body())
                .getAsJsonObject();
        mcp.stop();
        Path f = tmp.resolve("config/oauth-state.json");
        JsonObject st = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        for (var e : st.getAsJsonObject("rotated").entrySet()) e.getValue().getAsJsonObject().addProperty("at", System.currentTimeMillis() - 60_000);
        Files.writeString(f, st.toString());
        startMcp();
        assertEquals(400, form("/token", Map.of("grant_type", "refresh_token", "refresh_token", rv0, "client_id", cid2), Map.of()).statusCode());
        assertEquals(1, logCount("oauth.refresh_reuse"), "重启后过了宽限期的旧 refresh 仍判定被盗");
        assertEquals(400, form("/token", Map.of("grant_type", "refresh_token", "refresh_token", v1.get("refresh_token").getAsString(),
                "client_id", cid2), Map.of()).statusCode());
        // 绝对期限：首次授权在 91 天前（refresh_max_days 默认 90）→ 必须重新批准
        JsonObject u = fullFlow(token);
        mcp.stop();
        st = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        for (var e : st.getAsJsonObject("refresh_tokens").entrySet()) {
            e.getValue().getAsJsonObject().addProperty("grant_start", System.currentTimeMillis() - 91L * 86_400_000L);
        }
        Files.writeString(f, st.toString());
        startMcp();
        HttpResponse<String> old = form("/token", Map.of("grant_type", "refresh_token", "refresh_token", u.get("refresh_token").getAsString(),
                "client_id", u.get("client_id").getAsString()), Map.of());
        assertEquals(400, old.statusCode());
        assertTrue(old.body().contains("re-authorize"), old.body());
    }

    /** 授权请求没带 redirect_uri（客户端只注册了一个）：换令牌时也可以不带；带了 redirect_uri 的授权，换令牌时必须带。 */
    @Test
    void omittedRedirectUriRoundTrip() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        String q = authorizeQuery(cid, CHATGPT, "S256").replace("&redirect_uri=" + enc(CHATGPT), "");
        for (boolean given : new boolean[]{false, true}) {
            String id = requestId(get(given ? authorizeQuery(cid, CHATGPT, "S256") : q).body());
            String code = query(form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base))
                    .headers().firstValue("Location").orElseThrow()).get("code");
            int st = form("/token", Map.of("grant_type", "authorization_code", "code", code, "code_verifier", VERIFIER, "client_id", cid), Map.of())
                    .statusCode();
            assertEquals(given ? 400 : 200, st, "redirect_uri given=" + given);
        }
    }

    /** 同一来源最多 10 个待批请求，满了淘汰这个来源最早的；别的来源（服主自己）的请求不受影响。 */
    @Test
    void pendingRequestsCappedPerSource() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        String mine = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body()); // 服主自己的浏览器（127.0.0.1）
        Map<String, String> flood = Map.of("X-Forwarded-For", "203.0.113.99");
        String first = null;
        for (int i = 0; i < 30; i++) {
            String id = requestId(send("GET", authorizeQuery(cid, CHATGPT, "S256"), null, null, flood).body());
            if (first == null) first = id;
        }
        assertEquals(400, form("/authorize", Map.of("request", first, "approval", token, "decision", "approve"), Map.of("Origin", base)).statusCode());
        assertEquals(302, form("/authorize", Map.of("request", mine, "approval", token, "decision", "approve"), Map.of("Origin", base)).statusCode());
    }
    @Test
    void consoleApprovalIsBoundToTheRequestOnScreen() throws Exception {
        String cid = register(CHATGPT, "none", Map.of()).get("client_id").getAsString();
        String page = get(authorizeQuery(cid, CHATGPT, "S256")).body();
        String id = requestId(page), no = confirmNo(page);
        assertTrue(page.contains("/aibot remote approve " + no));
        assertTrue(mcp.pendingRequests().contains(no) && mcp.pendingRequests().contains("chatgpt.com"));
        // 还没在后台批准就点继续：提示去后台执行，不算失败、不跳转
        HttpResponse<String> notYet = form("/authorize", Map.of("request", id, "decision", "approve"), Map.of("Origin", base));
        assertEquals(200, notYet.statusCode());
        assertTrue(notYet.body().contains("还没有在服务器后台执行"));
        assertEquals(0, logCount("remote.denied_auth"));
        // 另一个请求的确认号批不了这个请求
        String cid2 = register("https://claude.ai/api/mcp/auth_callback", "none", Map.of()).get("client_id").getAsString();
        String no2 = confirmNo(get(authorizeQuery(cid2, "https://claude.ai/api/mcp/auth_callback", "S256")).body());
        assertTrue(mcp.approve(no2).startsWith("已批准"));
        assertEquals(200, form("/authorize", Map.of("request", id, "decision", "approve"), Map.of("Origin", base)).statusCode());
        assertTrue(mcp.approve("ZZZZZZ").startsWith("没有确认号"));
        assertTrue(mcp.approve(no.toLowerCase()).startsWith("已批准"));      // 不分大小写
        HttpResponse<String> ok = form("/authorize", Map.of("request", id, "decision", "approve"), Map.of("Origin", base));
        assertEquals(302, ok.statusCode(), ok.body());
        String code = query(ok.headers().firstValue("Location").orElseThrow()).get("code");
        assertEquals(200, form("/token", Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", CHATGPT,
                "code_verifier", VERIFIER, "client_id", cid), Map.of()).statusCode());
        assertFalse(mcp.pendingRequests().contains(no));
        assertEquals(2, logCount("oauth.owner_approved")); // no2 和 no 各一次
    }
    @Test
    void confidentialClientWithBasicAuth() throws Exception {
        JsonObject c = register(CHATGPT, "client_secret_basic", Map.of());
        String cid = c.get("client_id").getAsString(), secret = c.get("client_secret").getAsString();
        String id = requestId(get(authorizeQuery(cid, CHATGPT, "S256")).body());
        String code = query(form("/authorize", Map.of("request", id, "approval", token, "decision", "approve"), Map.of("Origin", base))
                .headers().firstValue("Location").orElseThrow()).get("code");
        Map<String, String> f = Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", CHATGPT, "code_verifier", VERIFIER);
        String wrong = Base64.getEncoder().encodeToString((cid + ":nope").getBytes(StandardCharsets.UTF_8));
        assertEquals(401, form("/token", f, Map.of("Authorization", "Basic " + wrong)).statusCode());
        String right = Base64.getEncoder().encodeToString((cid + ":" + secret).getBytes(StandardCharsets.UTF_8));
        // 上一次客户端认证失败没有动授权码（认证在取码之前），这次可以换到
        assertEquals(200, form("/token", f, Map.of("Authorization", "Basic " + right)).statusCode());
        assertFalse(Files.readString(tmp.resolve("config/oauth-state.json")).contains(secret));
    }

    @Test
    void stateSurvivesRestartAndRevokeAllClearsEverything() throws Exception {
        JsonObject t = fullFlow(token);
        String state = Files.readString(tmp.resolve("config/oauth-state.json"));
        assertFalse(state.contains(t.get("refresh_token").getAsString()) || state.contains(t.get("access_token").getAsString()));
        mcp.stop();
        startMcp();
        // access token 只在内存：重启后失效；refresh token 存了哈希：还能续
        assertEquals(401, mcpCall(t.get("access_token").getAsString()).statusCode());
        HttpResponse<String> r = form("/token", Map.of("grant_type", "refresh_token", "refresh_token", t.get("refresh_token").getAsString(),
                "client_id", t.get("client_id").getAsString()), Map.of());
        assertEquals(200, r.statusCode(), r.body());
        String at = JsonParser.parseString(r.body()).getAsJsonObject().get("access_token").getAsString();
        assertEquals(200, mcpCall(at).statusCode());
        assertTrue(mcp.revokeAll().contains("1 个"));
        assertEquals(401, mcpCall(at).statusCode());
        assertTrue(mcp.oauthClients().contains("没有"));
        assertEquals(200, mcpCall(token).statusCode()); // 固定令牌不受影响
    }

    @Test
    void revokeSingleClient() throws Exception {
        JsonObject a = fullFlow(token);
        JsonObject b = fullFlow(token);
        assertTrue(mcp.revoke(a.get("client_id").getAsString()).startsWith("已吊销"));
        assertEquals(401, mcpCall(a.get("access_token").getAsString()).statusCode());
        assertEquals(200, mcpCall(b.get("access_token").getAsString()).statusCode());
        assertTrue(mcp.revoke("mc-nobody").startsWith("没有"));
    }
}
