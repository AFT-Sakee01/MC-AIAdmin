package io.github.aftsakee.aiadmin.remote;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Cidr;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 远程接口的 HTTP 闸门（MiniHttp 的 early / handle / done 三个回调）：决定一个请求能不能进门，进门后的 /mcp 交给 {@link Mcp}（McpProtocol），
 * OAuth 端点交给 {@link OAuthProvider}。
 * <p>安全边界（按顺序检查，顺序不能打乱，见 AGENTS.md）：
 * <ol>
 *   <li>来源 IP 必须在 remote.allowed_cidrs（默认只有 Tailscale 段和本机）；经 Funnel 进来的都是 127.0.0.1，此时真正的防线是令牌 + 认证失败封禁；</li>
 *   <li>路由：只有 /mcp 和 OAuth 的几个路径，其他一律 404；</li>
 *   <li>/mcp 带 Origin 头的请求只接受 localhost / 127.0.0.1 / [::1] / 监听地址 / 对外地址的主机（防 DNS 重绑定，MCP 规范要求）；
 *       Origin: null 之类解析不出主机的一律拒绝。Origin 不对直接 403，不去验令牌、也不计入认证失败；</li>
 *   <li>Authorization: Bearer：固定令牌（常量时间比较）或有效的 OAuth access token（按哈希查表）；两种都不对算一次认证失败；令牌不写日志；</li>
 *   <li>按真实来源的认证失败封禁（AuthGuard）：只拦失败的请求和批准表单——持有效令牌的请求照常放行；封禁期间继续失败会续封
 *       （一律 429 + Retry-After）。没带有效令牌、要读请求体的请求每个来源最多 {@link #UNAUTH_PER_SOURCE} 个、全局最多 {@link #UNAUTH_TOTAL} 个同时在读
 *       （/token 另算 {@link #UNAUTH_TOKEN_TOTAL} 个），请求体超过 16 KiB 读完请求头就 413、必须在 {@link #UNAUTH_BODY_MS} 内传完（防慢速请求体占线程）；</li>
 *   <li>之后的规则/限流（rules.json 隐藏工具、只读模式、每分钟/每小时上限）在 McpProtocol / RemoteTools。</li>
 * </ol>
 * early 在读请求体之前就做第 1、3、4、5 条（慢速客户端占不住线程；路由的 404 在 handle 里回），handle 读完请求体后再按同样顺序完整检查一遍
 * （设置可能在两者之间重读、OAuth 令牌可能刚好过期）；两处共用同一组检查方法，来源、Origin、401 的判断各只有一份。
 * <p>线程：三个回调被 MiniHttp 的多个处理线程（16 个）同时调用。共享状态：AuthGuard 自己加锁；"未认证在读"名额
 * （unauthInflight / otherInflight / tokenInflight）只在 unauthInflight 的监视器里改，锁里只做计数、不做 IO；unauthHeads 是同步的 IdentityHashMap，
 * 按请求对象身份记 early 占了哪个名额，done 里原样释放（MiniHttp 保证 early 放行的请求无论成败都回调 done）。令牌、OAuth、监听地址每次从
 * McpServer 的 volatile 字段读（重启时会换）。本对象和 AuthGuard 跟着 McpServer 活，重启远程接口不清封禁表。
 */
final class RemoteGate {
    /**
     * 没带有效令牌、正在读请求体/处理的请求的名额（线程池 16 个）：每个来源最多 UNAUTH_PER_SOURCE 个；
     * 发现/注册/授权全局最多 UNAUTH_TOTAL 个；/token（已授权连接器的续期）另算、最多 UNAUTH_TOKEN_TOTAL 个——
     * 不和别人的慢请求抢名额，又不能无限占线程。两类合计 12，持有效令牌的请求（含长轮询）至少还有 4 个线程。
     */
    static final int UNAUTH_PER_SOURCE = 3;
    static final int UNAUTH_TOTAL = 8;
    static final int UNAUTH_TOKEN_TOTAL = 4;
    /** 没带有效令牌的请求，读完请求头后请求体必须在这么久内传完。 */
    static final long UNAUTH_BODY_MS = 5_000L;

    /** 通过闸门、持有效令牌的 /mcp 请求交给谁（McpProtocol::handle）。who = 审计日志里的来源（含 OAuth 客户端名），principal = 推送订阅的归属。 */
    @FunctionalInterface
    interface Mcp {
        MiniHttp.Response handle(MiniHttp.Request req, String who, String principal);
    }

    private final Supplier<BotConfig> cfg;
    private final JsonlLog log;
    private final Supplier<String> token;
    private final Supplier<OAuthProvider> oauth;
    private final Supplier<String> boundUrl;
    private final Mcp mcp;
    private final AuthGuard guard = new AuthGuard();
    /** 每个来源占用的名额数；otherInflight / tokenInflight 是两类的全局计数。三者都只在 unauthInflight 的锁里改。 */
    private final Map<String, Integer> unauthInflight = new HashMap<>();
    private int otherInflight;
    private int tokenInflight;

    private record Slot(String client, boolean token) {}

    private final Map<MiniHttp.Request, Slot> unauthHeads = Collections.synchronizedMap(new IdentityHashMap<>());

    RemoteGate(Supplier<BotConfig> cfg, JsonlLog log, Supplier<String> token, Supplier<OAuthProvider> oauth, Supplier<String> boundUrl, Mcp mcp) {
        this.cfg = cfg;
        this.log = log;
        this.token = token;
        this.oauth = oauth;
        this.boundUrl = boundUrl;
        this.mcp = mcp;
    }

    /**
     * 读完请求头、还没读请求体时的检查（MiniHttp 回调）：来源网段不对、Origin 不对、没带有效令牌访问 /mcp、封禁中的来源提交批准表单、
     * 未认证请求体过大或同时在读的太多——这些直接拒绝，不再等慢速客户端把请求体传完（防止占住处理线程）。返回 null = 继续读请求体。
     */
    MiniHttp.Response early(MiniHttp.Request head) {
        BotConfig.Remote r = cfg.get().remote;
        long now = System.currentTimeMillis();
        MiniHttp.Response denied = checkSource(head, r, now);
        if (denied != null) return denied;
        String path = pathOf(head.path());
        String client = AuthGuard.realClient(head.remote(), head.header("x-forwarded-for"));
        if (path.equals("/mcp")) {
            // 顺序（AGENTS.md）：来源网段 → Origin → 令牌 → 规则/限流。没带有效令牌的 /mcp 不需要请求体：
            // 读完请求头就回 401（或封禁中的 429），慢速请求体占不住线程；持有效令牌的照常读请求体，不占下面的名额
            denied = checkOrigin(head);
            if (denied != null) return denied;
            return authorizedAs(head) != null ? null : mcpUnauthorized(head, client, guard.blockedSeconds(client, now), r, now);
        }
        long blocked = guard.blockedSeconds(client, now);
        if (blocked > 0 && path.equals("/authorize") && head.method().equals("POST")) return tooMany(blocked);
        // 没带有效令牌的请求（发现、注册、授权、换令牌）要读请求体：限制每个来源和全局同时在读的数量，
        // 慢速逐字节发送占不满处理线程，持有效令牌的请求（含云端值守的长轮询）永远有线程可用。
        // OAuth 端点的请求体都很小：超过 16 KiB 直接 413（注册按 RFC 7591 的错误格式回）；请求体必须在读完请求头后 5 秒内传完
        if (bodyLargerThan(head, OAuthProvider.REGISTER_MAX_BODY)) {
            return path.equals("/register")
                    ? MiniHttp.Response.json(413, "{\"error\":\"invalid_client_metadata\",\"error_description\":\"registration request too large\"}")
                    : MiniHttp.Response.json(413, "{\"error\":\"request too large\"}");
        }
        MiniHttp.limitBodyTime(UNAUTH_BODY_MS);
        // /token 单独计数：已授权的连接器续期不能被别人的慢请求挤掉，但它自己也有上限（见 UNAUTH_TOKEN_TOTAL）
        boolean tokenPath = path.equals("/token");
        synchronized (unauthInflight) {
            int mine = unauthInflight.getOrDefault(client, 0);
            boolean full = tokenPath ? tokenInflight >= UNAUTH_TOKEN_TOTAL : otherInflight >= UNAUTH_TOTAL;
            if (mine >= UNAUTH_PER_SOURCE || full) return MiniHttp.Response.json(503, "{\"error\":\"busy\"}");
            unauthInflight.merge(client, 1, Integer::sum);
            if (tokenPath) tokenInflight++;
            else otherInflight++;
        }
        unauthHeads.put(head, new Slot(client, tokenPath));
        return null;
    }

    /** MiniHttp 在一个请求处理完（或失败）后回调：释放 early 里占的"未认证在读"名额。 */
    void done(MiniHttp.Request head) {
        Slot s = unauthHeads.remove(head);
        if (s == null) return;
        synchronized (unauthInflight) {
            unauthInflight.computeIfPresent(s.client(), (k, v) -> v <= 1 ? null : v - 1);
            if (s.token()) tokenInflight = Math.max(0, tokenInflight - 1);
            else otherInflight = Math.max(0, otherInflight - 1);
        }
    }

    /** 读完请求体后的完整检查（MiniHttp 回调）：OAuth 端点交给 OAuthProvider，持有效令牌的 /mcp 交给 McpProtocol。 */
    MiniHttp.Response handle(MiniHttp.Request req) {
        BotConfig.Remote r = cfg.get().remote;
        long now = System.currentTimeMillis();
        MiniHttp.Response denied = checkSource(req, r, now);
        if (denied != null) return denied;
        String client = AuthGuard.realClient(req.remote(), req.header("x-forwarded-for"));
        // 封禁只拦"失败的请求"和批准表单：持有效令牌的请求照常放行。否则别人在同一个出口 IP（ChatGPT、claude.ai 的服务器）上
        // 故意输错几次，服主已经授权的连接器也会被 429 挡住。
        long blocked = guard.blockedSeconds(client, now);
        String path = pathOf(req.path());
        int q = req.path().indexOf('?');
        String query = q >= 0 ? req.path().substring(q + 1) : null;
        OAuthProvider oa = oauth.get();
        boolean oauthOn = oa != null && oa.enabled();
        if (oauthOn && OAuthProvider.handles(path)) {
            if (blocked > 0 && path.equals("/authorize") && req.method().equals("POST")) return tooMany(blocked);
            // OAuth 端点：失败（批准口令错、/token 认证错）计入同一套 AuthGuard；批准口令可以是固定令牌
            MiniHttp.Response resp = oa.handle(req, path, query, client, new OAuthProvider.Hooks() {
                @Override
                public void onFailure(String endpoint) {
                    authFailed(req, client, r, now, endpoint);
                }

                @Override
                public boolean ownerProof(String secret) {
                    return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.get().getBytes(StandardCharsets.UTF_8));
                }
            });
            // /token：成功的照常返回（拿着有效授权码/refresh token 的客户端不受影响）；请求前就在封禁中的来源，失败时回 429
            if (blocked > 0 && path.equals("/token") && (resp.status() == 400 || resp.status() == 401)) return tooMany(blocked);
            return resp;
        }
        if (!path.equals("/mcp")) return MiniHttp.Response.json(404, "{\"error\":\"not found\"}");
        denied = checkOrigin(req);
        if (denied != null) return denied;
        String as = authorizedAs(req);
        if (as == null) return mcpUnauthorized(req, client, blocked, r, now);
        if (blocked == 0) guard.recordSuccess(client);
        String who = as.isEmpty() ? client : client + " oauth:" + oa.clientLabel(as); // 审计日志里分得清是哪个 OAuth 客户端
        String principal = as.isEmpty() ? "token" : as; // 推送订阅的归属
        return mcp.handle(req, who, principal);
    }

    /** 来源网段检查（allowed_cidrs 只看 TCP 来源；经 Funnel 进来的都是 127.0.0.1，此时真正的防线是令牌 + 认证失败封禁）。 */
    private MiniHttp.Response checkSource(MiniHttp.Request req, BotConfig.Remote r, long now) {
        if (Cidr.anyContains(r.allowedCidrs, req.remote())) return null;
        if (guard.shouldLogDeniedIp(req.remote().getHostAddress(), now, r.authFailWindowSeconds * 1000L)) {
            log.warn("remote", "remote.denied_ip", "拒绝来源 " + req.remote().getHostAddress() + "（同一来源 " + r.authFailWindowSeconds + " 秒内只记一次）", null, null);
        }
        return MiniHttp.Response.json(403, "{\"error\":\"source address not allowed\"}");
    }

    /** /mcp 的 Origin 检查：不带 Origin（非浏览器客户端）放行；带了就必须是允许的主机，否则 403（防 DNS 重绑定）。 */
    private MiniHttp.Response checkOrigin(MiniHttp.Request req) {
        String origin = req.header("origin");
        return origin == null || originAllowed(origin) ? null : MiniHttp.Response.json(403, "{\"error\":\"origin not allowed\"}");
    }

    /** Origin: null、不是 URL、没有主机的一律不放行（用户明确要求，不放松）。 */
    private boolean originAllowed(String origin) {
        try {
            String host = URI.create(origin).getHost();
            if (host == null) return false;
            String bu = boundUrl.get();
            String bound = bu == null ? "" : URI.create(bu).getHost();
            String pub = cfg.get().remote.publicBaseUrl.isBlank() ? "" : URI.create(cfg.get().remote.publicBaseUrl).getHost();
            // 主机名不区分大小写（浏览器发的 Origin 一般是小写，设置里可能写了大写）
            return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equalsIgnoreCase(bound)
                    || host.equalsIgnoreCase(pub);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Bearer 是固定令牌（返回 ""）或有效的 OAuth access token（返回 client_id）；都不是返回 null。固定令牌常量时间比较，OAuth 按哈希查表。 */
    private String authorizedAs(MiniHttp.Request req) {
        String auth = req.header("authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String presented = auth.substring(7).trim();
        if (MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token.get().getBytes(StandardCharsets.UTF_8))) return "";
        OAuthProvider oa = oauth.get();
        return oa == null ? null : oa.validAccess(presented);
    }

    /**
     * /mcp 没带有效令牌（early 和 handle 共用）：没带 Authorization 回 401（正常的发现流程：客户端先探一下再走 OAuth，不算失败）；
     * 带了错的计入认证失败（封禁中的失败也计数、续封），封禁中回 429，否则 401 + invalid_token。
     */
    private MiniHttp.Response mcpUnauthorized(MiniHttp.Request req, String client, long blocked, BotConfig.Remote r, long now) {
        OAuthProvider oa = oauth.get();
        boolean oauthOn = oa != null && oa.enabled();
        // 告诉客户端去哪里找授权信息（RFC 9728）；地址来自设置里的对外地址，不从 Host 头推断
        String www = oauthOn ? "Bearer resource_metadata=\"" + oa.resourceMetadataUrl() + "\"" : "Bearer";
        if (req.header("authorization") == null) return unauthorized(www);
        authFailed(req, client, r, now, "mcp");
        if (blocked > 0) return tooMany(blocked);
        return unauthorized(oauthOn ? www + ", error=\"invalid_token\"" : www);
    }

    private static MiniHttp.Response unauthorized(String wwwAuthenticate) {
        return new MiniHttp.Response(401, "application/json", "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("WWW-Authenticate", wwwAuthenticate));
    }

    private static MiniHttp.Response tooMany(long seconds) {
        return new MiniHttp.Response(429, "application/json", "{\"error\":\"too many failed attempts\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("Retry-After", Long.toString(seconds)));
    }

    /** 一次认证失败：计入 AuthGuard（够次数就封禁），日志按来源合并。endpoint 只是记录用。 */
    private void authFailed(MiniHttp.Request req, String client, BotConfig.Remote r, long now, String endpoint) {
        long windowMs = r.authFailWindowSeconds * 1000L;
        boolean identifiable = !req.remote().isLoopbackAddress() || req.header("x-forwarded-for") != null;
        AuthGuard.Failure f = guard.recordFailure(client, now, r.authFailLimit, windowMs, r.authBlockSeconds * 1000L, identifiable);
        if (f.blocked()) {
            log.warn("remote", "remote.auth_blocked", "来源 " + client + " 在 " + r.authFailWindowSeconds + " 秒内认证失败 " + f.count() + " 次，封禁 "
                    + r.authBlockSeconds + " 秒", null,
                    JsonlLog.data("client", client, "endpoint", endpoint, "failures", f.count(), "unlogged_before", f.suppressedBefore()));
        } else if (f.log()) {
            log.warn("remote", "remote.denied_auth", "认证失败（" + endpoint + "），来源 " + client + "（同一来源 " + r.authFailWindowSeconds + " 秒内只记第一次）",
                    null, JsonlLog.data("client", client, "endpoint", endpoint));
        }
    }

    /**
     * 请求头声明的请求体是否超过 max。MiniHttp.readHead 已校验过 Content-Length（不是整数回 400、超过 1 MiB 回 413），
     * 这里解析失败只可能是没有校验的调用方，按"不超过"处理、交给读请求体时报错。
     */
    private static boolean bodyLargerThan(MiniHttp.Request head, long max) {
        String cl = head.header("content-length");
        try {
            return cl != null && Long.parseLong(cl) > max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String pathOf(String raw) {
        int q = raw.indexOf('?');
        return q >= 0 ? raw.substring(0, q) : raw;
    }
}
