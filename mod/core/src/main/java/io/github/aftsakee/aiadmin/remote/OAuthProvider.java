package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 远程接口的 OAuth 2.1 授权服务（MCP 授权规范：RFC 9728 受保护资源元数据、RFC 8414 授权服务器元数据、RFC 7591 动态注册、PKCE）。
 * 给 ChatGPT 连接器、claude.ai 这类不能填固定请求头的客户端用；固定令牌方式不受影响。
 * <p>安全边界（接口经 Tailscale Funnel 暴露在公网）：
 * <ul>
 *   <li><b>批准必须证明是服主</b>：动态注册对谁都开放，所以批准要么在服务器后台执行 {@code /aibot remote approve <确认号>}
 *       （确认号显示在服主自己打开的批准页上，绑定这一个授权请求；指令要求权限等级 4，代理的 run_command 在代码层被禁止执行
 *       aibot remote），要么在批准页输入固定令牌。没有"谁都能点"的按钮。</li>
 *   <li>注册：redirect_uris 只允许 allowed_redirect_hosts 里的主机，https（本机地址可 http），不带片段和用户信息，最多 5 个；
 *       请求体最大 16 KiB，解析和校验在锁外做。存储压力只来自"还没被批准过的客户端"：最多 max_unused_clients 个，1 小时后自动清理，
 *       满了从未用客户端最多的来源里淘汰最早的一个（退一步再淘汰注册超过 10 分钟的最旧一个）；另有每个来源每 10 分钟的次数限制。没有全服共享的次数上限——共享出口 IP（ChatGPT、claude.ai
 *       的服务器）上的其他人只能耗掉自己那个来源的额度，耗不掉全服的。已批准客户端的总数上限在批准时检查。</li>
 *   <li>授权：redirect_uri 与注册时<b>完全一致</b>才接受（客户端和地址不对时只显示错误页、不跳转）；PKCE 只接受 S256；
 *       授权请求的参数存在服务端，批准表单只带一个随机请求号（同时充当 CSRF 令牌），POST 还校验 Origin；
 *       每个来源最多 10 个待批请求（满了淘汰该来源最早的），全局满了从占得最多的来源淘汰、服主已在后台批准的不淘汰；
 *       批准页禁止被嵌入（X-Frame-Options、CSP frame-ancestors），表单只能提交到本站和该客户端的回调地址。</li>
 *   <li>授权码：随机 32 字节、60 秒过期、只能用一次（取出即删除，后续校验失败也作废），绑定 client_id、redirect_uri、code_challenge。</li>
 *   <li>令牌：access token 有过期时间，只在内存里存哈希；refresh token 存哈希到 oauth-state.json，每用一次换新的，
 *       有效期从首次授权起算不超过 refresh_max_days（到期必须重新批准，避免变成永久凭据）。已换掉的旧 refresh token
 *       （记录持久化，重启后也认得）在 30 秒内再出现视为网络重试：作废上一次签发的那一对、重新签发；超过 30 秒再出现 = 可能被盗，
 *       吊销该客户端的全部令牌。/aibot remote revoke-all 一键清空。</li>
 *   <li>失败计数：批准口令错误、/token 的客户端认证或授权码/刷新令牌错误都通过 Hooks.onFailure 计入 AuthGuard。</li>
 *   <li>令牌、授权码、client secret 都不写日志；审计日志只记 client_id、客户端名和来源。</li>
 * </ul>
 * 所有改状态的操作加同一把锁；状态文件原子替换写入，创建时就是 660 权限，位于 config/aiadmin/（文件工具永远读不到这个目录）。
 */
public final class OAuthProvider {
    public static final String SCOPE = "mcp";
    static final long CODE_MS = 60_000L;
    static final long PENDING_MS = 10 * 60_000L;
    static final int PENDING_MAX = 500;
    static final int PENDING_PER_SOURCE = 10;
    static final int PENDING_ATTEMPTS = 5;
    static final long UNUSED_CLIENT_TTL_MS = 3600_000L;
    static final long UNUSED_EVICT_AFTER_MS = 10 * 60_000L;
    static final long REGISTER_WINDOW_MS = 10 * 60_000L;
    static final long REFRESH_RETRY_GRACE_MS = 30_000L;
    static final int REGISTER_MAX_BODY = 16 * 1024;
    static final int MAX_REDIRECT_URIS = 5;
    static final int ROTATED_MAX = 20_000;
    private static final SecureRandom RNG = new SecureRandom();
    private static final String CONFIRM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private record Client(String id, String name, List<String> redirectUris, String authMethod, String secretHash, long created, long lastUsed,
                          String source) {
        boolean used() {
            return lastUsed > 0;
        }
    }

    private record Pending(String clientId, String confirm, String redirectUri, boolean redirectGiven, String challenge, String state, String source,
                           long created, long expires, int attempts, boolean ownerApproved) {}

    private record Code(String clientId, String redirectUri, boolean redirectGiven, String challenge, long expires, long grantStart) {}

    private record Tok(String clientId, long expires, long grantStart) {}

    /**
     * 已换掉的 refresh token：at = 换掉的时间，child = 当时签发的新 refresh token 的哈希。
     * poisoned = 这张是"重试补发时被作废的那张新 token"：它以后任何时候再出现都说明有两方各拿了一份（客户端重试拿到补发的，
     * 另一方手里还有被作废的这张）——按被盗处理，立即吊销该客户端全部令牌，不给宽限期。
     */
    private record Rotated(String clientId, long at, String child, long grantStart, boolean poisoned) {}

    /** onFailure：一次认证失败（RemoteGate 计入 AuthGuard 并按合并规则写日志）；ownerProof：输入的是否是固定令牌（常量时间比较）。 */
    public interface Hooks {
        void onFailure(String endpoint);

        boolean ownerProof(String secret);
    }

    private final Supplier<BotConfig> cfg;
    private final JsonlLog log;
    private final Path stateFile;
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private final Map<String, Tok> refresh = new HashMap<>();          // 哈希 → 令牌
    private final Map<String, Tok> access = new HashMap<>();           // 哈希 → 令牌（只在内存）
    private final Map<String, Rotated> rotated = new LinkedHashMap<>(); // 已换掉的 refresh 哈希 → 记录（检测重放）
    private final Map<String, String> pairAccess = new HashMap<>();      // refresh 哈希 → 同一次签发的 access 哈希（只在内存，和 access 一样）
    /**
     * 宽限期内的原样重放：旧 refresh 哈希 → 那次换出来的完整响应（明文令牌，只在内存、最多 REFRESH_RETRY_GRACE_MS，不落盘不进日志）。
     * 多进程的连接器（ChatGPT、claude.ai）在 access 过期瞬间会用同一个 refresh 并发刷新：以前第二个请求会作废第一个签发的那一对、
     * 并把它标成 poisoned，先到的进程随后拿那对刷新就触发"全部吊销"，服主得重新批准。现在宽限期内重复的请求拿到同一对令牌。
     */
    private record Replay(String clientId, String body, long at) {}
    private final Map<String, Replay> replays = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>(); // 请求号 → 待批请求
    private final Map<String, Code> codes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> registrationsBySource = new HashMap<>();

    public OAuthProvider(Supplier<BotConfig> cfg, JsonlLog log, Path stateFile) {
        this.cfg = cfg;
        this.log = log;
        this.stateFile = stateFile;
        load();
    }

    Path stateFile() {
        return stateFile;
    }

    /** 开着并且配置了对外地址才提供 OAuth。 */
    public boolean enabled() {
        BotConfig.Remote r = cfg.get().remote;
        return r.oauth.enabled && !r.publicBaseUrl.isBlank();
    }

    String base() {
        return cfg.get().remote.publicBaseUrl;
    }

    String resource() {
        return base() + "/mcp";
    }

    public String resourceMetadataUrl() {
        return base() + "/.well-known/oauth-protected-resource";
    }

    static boolean handles(String path) {
        return isMetadata(path) || path.equals("/authorize") || path.equals("/token") || path.equals("/register");
    }

    static boolean isMetadata(String path) {
        return path.equals("/.well-known/oauth-protected-resource") || path.equals("/.well-known/oauth-protected-resource/mcp")
                || path.equals("/.well-known/oauth-authorization-server");
    }

    // ———————————————————— 路由 ————————————————————

    MiniHttp.Response handle(MiniHttp.Request req, String path, String query, String source, Hooks hooks) {
        String m = req.method();
        switch (path) {
            case "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp" -> {
                return m.equals("GET") ? json(200, protectedResource()) : MiniHttp.Response.empty(405, Map.of("Allow", "GET"));
            }
            case "/.well-known/oauth-authorization-server" -> {
                return m.equals("GET") ? json(200, serverMetadata()) : MiniHttp.Response.empty(405, Map.of("Allow", "GET"));
            }
            case "/register" -> {
                return m.equals("POST") ? register(req, source) : MiniHttp.Response.empty(405, Map.of("Allow", "POST"));
            }
            case "/authorize" -> {
                if (m.equals("GET")) return authorizePage(parseForm(query == null ? "" : query), source);
                if (m.equals("POST")) return authorizeSubmit(req, source, hooks);
                return MiniHttp.Response.empty(405, Map.of("Allow", "GET, POST"));
            }
            case "/token" -> {
                return m.equals("POST") ? token(req, source, hooks) : MiniHttp.Response.empty(405, Map.of("Allow", "POST"));
            }
            default -> {
                return MiniHttp.Response.json(404, "{\"error\":\"not found\"}");
            }
        }
    }

    JsonObject protectedResource() {
        JsonObject o = new JsonObject();
        o.addProperty("resource", resource());
        o.add("authorization_servers", arr(base()));
        o.add("bearer_methods_supported", arr("header"));
        o.add("scopes_supported", arr(SCOPE));
        o.addProperty("resource_name", "Minecraft AI 管理机器人");
        return o;
    }

    JsonObject serverMetadata() {
        String b = base();
        JsonObject o = new JsonObject();
        o.addProperty("issuer", b);
        o.addProperty("authorization_endpoint", b + "/authorize");
        o.addProperty("token_endpoint", b + "/token");
        o.addProperty("registration_endpoint", b + "/register");
        o.add("response_types_supported", arr("code"));
        o.add("grant_types_supported", arr("authorization_code", "refresh_token"));
        o.add("code_challenge_methods_supported", arr("S256"));
        o.add("token_endpoint_auth_methods_supported", arr("none", "client_secret_post", "client_secret_basic"));
        o.add("scopes_supported", arr(SCOPE));
        o.addProperty("authorization_response_iss_parameter_supported", true);
        return o;
    }

    // ———————————————————— 动态注册 ————————————————————

    /** 解析和校验在锁外（大请求不卡住令牌校验），只有改状态的部分加锁。 */
    MiniHttp.Response register(MiniHttp.Request req, String source) {
        if (req.body().length > REGISTER_MAX_BODY) return oauthError(413, "invalid_client_metadata", "registration request too large");
        BotConfig.OAuth o = cfg.get().remote.oauth;
        JsonObject body;
        try {
            JsonElement e = JsonParser.parseString(new String(req.body(), StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return oauthError(400, "invalid_client_metadata", "body must be a JSON object");
            body = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return oauthError(400, "invalid_client_metadata", "invalid JSON");
        }
        if (!body.has("redirect_uris") || !body.get("redirect_uris").isJsonArray()) return oauthError(400, "invalid_redirect_uri", "redirect_uris required");
        JsonArray raw = body.getAsJsonArray("redirect_uris");
        if (raw.isEmpty() || raw.size() > MAX_REDIRECT_URIS) return oauthError(400, "invalid_redirect_uri", "1-" + MAX_REDIRECT_URIS + " redirect_uris required");
        Set<String> uris = new LinkedHashSet<>();
        for (JsonElement e : raw) {
            if (!e.isJsonPrimitive()) return oauthError(400, "invalid_redirect_uri", "redirect_uris must be strings");
            String u = e.getAsString();
            String why = checkRedirect(u, o.allowedRedirectHosts);
            if (why != null) return oauthError(400, "invalid_redirect_uri", why);
            uris.add(u);
        }
        if (!subset(body, "grant_types", Set.of("authorization_code", "refresh_token"))) return oauthError(400, "invalid_client_metadata", "unsupported grant_types");
        if (!subset(body, "response_types", Set.of("code"))) return oauthError(400, "invalid_client_metadata", "unsupported response_types");
        String method = str(body, "token_endpoint_auth_method", "none");
        if (!Set.of("none", "client_secret_post", "client_secret_basic").contains(method)) {
            return oauthError(400, "invalid_client_metadata", "unsupported token_endpoint_auth_method");
        }
        String name = cleanName(str(body, "client_name", ""));
        String id = "mc-" + random(18);
        String secret = method.equals("none") ? null : random(32);
        long now = System.currentTimeMillis();
        synchronized (this) {
            ArrayDeque<Long> mine = registrationsBySource.computeIfAbsent(source, k -> new ArrayDeque<>());
            while (!mine.isEmpty() && now - mine.peekFirst() > REGISTER_WINDOW_MS) mine.pollFirst();
            if (mine.size() >= o.registerPerSource) return oauthError(429, "too_many_requests", "registration rate limit for this source");
            if (registrationsBySource.size() > 1000) registrationsBySource.values().removeIf(ArrayDeque::isEmpty);
            purgeUnusedClients(now);
            if (unusedCount() >= o.maxUnusedClients && !evictOldestUnused(now)) {
                return oauthError(429, "too_many_requests", "too many pending registrations, try again later");
            }
            clients.put(id, new Client(id, name, List.copyOf(uris), method, secret == null ? null : sha(secret), now, 0, source));
            mine.addLast(now);
            save();
        }
        log.info("remote", "oauth.register", "OAuth 客户端注册：" + (name.isEmpty() ? id : name), null,
                JsonlLog.data("client_id", id, "client_name", name, "redirect_hosts", hosts(new ArrayList<>(uris)), "client", source));
        JsonObject r = new JsonObject();
        r.addProperty("client_id", id);
        r.addProperty("client_id_issued_at", now / 1000);
        if (secret != null) {
            r.addProperty("client_secret", secret);
            r.addProperty("client_secret_expires_at", 0);
        }
        r.add("redirect_uris", arr(uris.toArray(new String[0])));
        if (!name.isEmpty()) r.addProperty("client_name", name);
        r.addProperty("token_endpoint_auth_method", method);
        r.add("grant_types", arr("authorization_code", "refresh_token"));
        r.add("response_types", arr("code"));
        r.addProperty("scope", SCOPE);
        return noStore(json(201, r));
    }

    /** redirect_uri 是否可接受；可以返回 null，否则返回原因。 */
    static String checkRedirect(String u, List<String> allowedHosts) {
        if (u == null || u.isEmpty() || u.length() > 512) return "redirect_uri empty or too long";
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            return "redirect_uri is not a valid URI";
        }
        if (uri.getRawFragment() != null || uri.getRawUserInfo() != null || uri.getHost() == null) return "redirect_uri must not have fragment/userinfo";
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) return "redirect_uri must be https (http only for loopback)";
        if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            return "redirect_uri host not allowed by server owner: " + host;
        }
        return null;
    }

    private int unusedCount() {
        int n = 0;
        for (Client c : clients.values()) if (!c.used()) n++;
        return n;
    }

    /** 从没被批准过（没拿到过令牌）、注册超过 1 小时的客户端：多半是扫描器或放弃的尝试，直接清掉。 */
    private void purgeUnusedClients(long now) {
        if (clients.values().removeIf(c -> !c.used() && now - c.created() > UNUSED_CLIENT_TTL_MS)) {
            pending.values().removeIf(p -> !clients.containsKey(p.clientId()));
        }
    }

    /**
     * 未用客户端满了：从"未用客户端最多的来源"里淘汰它最早注册的一个（多个来源轮流灌注册，被淘汰的也是灌得最多的那个，
     * 服主自己的一两个注册不会先被挤掉）；如果那个来源的都还不到 10 分钟，退回旧规则：淘汰全局注册超过 10 分钟的最旧一个。
     */
    private boolean evictOldestUnused(long now) {
        Map<String, Integer> bySource = new HashMap<>();
        for (Client c : clients.values()) if (!c.used()) bySource.merge(c.source(), 1, Integer::sum);
        String top = bySource.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (top != null && bySource.get(top) > 1) {
            for (Iterator<Client> it = clients.values().iterator(); it.hasNext(); ) {
                Client c = it.next();
                if (!c.used() && c.source().equals(top)) {
                    it.remove();
                    pending.values().removeIf(p -> p.clientId().equals(c.id()));
                    return true;
                }
            }
        }
        for (Iterator<Client> it = clients.values().iterator(); it.hasNext(); ) {
            Client c = it.next();
            if (!c.used() && now - c.created() > UNUSED_EVICT_AFTER_MS) {
                it.remove();
                pending.values().removeIf(p -> p.clientId().equals(c.id()));
                return true;
            }
        }
        return false;
    }

    // ———————————————————— 授权页 ————————————————————

    synchronized MiniHttp.Response authorizePage(Map<String, String> q, String source) {
        long now = System.currentTimeMillis();
        Client c = clients.get(q.getOrDefault("client_id", ""));
        if (c == null) return errorPage(400, "不认识这个客户端（client_id 无效、已过期或已被吊销）。请回到原应用重新连接。");
        String redirect = q.get("redirect_uri");
        boolean given = redirect != null;
        if (redirect == null && c.redirectUris().size() == 1) redirect = c.redirectUris().get(0);
        if (redirect == null || !c.redirectUris().contains(redirect)) {
            return errorPage(400, "回调地址和这个客户端注册时的不一致，已拒绝。"); // 不跳转：地址不可信
        }
        String state = q.get("state");
        if (!"code".equals(q.get("response_type"))) return redirectError(redirect, "unsupported_response_type", state);
        String challenge = q.get("code_challenge");
        if (!"S256".equals(q.get("code_challenge_method")) || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
            return redirectError(redirect, "invalid_request", state); // PKCE 必须是 S256（不接受 plain）
        }
        String res = q.get("resource");
        if (res != null && !resourceMatches(res)) return redirectError(redirect, "invalid_target", state);
        addPending(now, source);
        String id = random(24);
        String confirm = newConfirm();
        pending.put(id, new Pending(c.id(), confirm, redirect, given, challenge, state, source, now, now + PENDING_MS, 0, false));
        return page(200, c, pending.get(id), id, null);
    }

    /**
     * 腾出位置：先清过期的；同一来源超过 10 个就淘汰这个来源最早的；全局超过上限时，从占得最多的来源里淘汰它最早的一个
     * （服主已在后台批准、等点"继续"的不淘汰）——外人要挤掉服主的请求，就得让自己的每个来源都比服主占得少，做不到。
     */
    private void addPending(long now, String source) {
        pending.values().removeIf(p -> p.expires() < now);
        int mine = 0;
        String oldestMine = null;
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            if (!e.getValue().source().equals(source)) continue;
            if (oldestMine == null) oldestMine = e.getKey();
            mine++;
        }
        if (mine >= PENDING_PER_SOURCE && oldestMine != null) pending.remove(oldestMine);
        while (pending.size() >= PENDING_MAX) {
            Map<String, Integer> bySource = new HashMap<>();
            for (Pending p : pending.values()) if (!p.ownerApproved()) bySource.merge(p.source(), 1, Integer::sum);
            String top = bySource.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (top == null) break;
            Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Pending p = it.next().getValue();
                if (p.source().equals(top) && !p.ownerApproved()) {
                    it.remove();
                    break;
                }
            }
        }
    }

    private String newConfirm() {
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(CONFIRM_ALPHABET.charAt(RNG.nextInt(CONFIRM_ALPHABET.length())));
            String s = sb.toString();
            if (pending.values().stream().noneMatch(p -> p.confirm().equals(s))) return s;
        }
    }

    synchronized MiniHttp.Response authorizeSubmit(MiniHttp.Request req, String source, Hooks hooks) {
        long now = System.currentTimeMillis();
        String origin = req.header("origin");
        // 只接受本站（对外地址 remote.public_base_url）。批准页必须用 Referrer-Policy: same-origin——
        // 用 no-referrer 时浏览器提交表单会发 Origin: null，这里就会误拒（1.0.0.15 线上实测）
        if (origin != null && !origin.equalsIgnoreCase(originOf(base()))) return errorPage(403, "请求来源不对（Origin 不是本站），已拒绝。");
        Map<String, String> f = parseForm(new String(req.body(), StandardCharsets.UTF_8));
        String id = f.getOrDefault("request", "");
        Pending p = pending.get(id);
        if (p == null || p.expires() < now) {
            pending.remove(id);
            return errorPage(400, "这个授权请求已过期或无效，请回到原应用重新连接。");
        }
        Client c = clients.get(p.clientId());
        if (c == null) {
            pending.remove(id);
            return errorPage(400, "这个客户端已被吊销。");
        }
        if ("deny".equals(f.get("decision"))) {
            pending.remove(id);
            log.info("remote", "oauth.denied", "服主拒绝了 OAuth 授权：" + label(c), null, JsonlLog.data("client_id", c.id(), "client", source));
            return redirectError(p.redirectUri(), "access_denied", p.state());
        }
        String proof = f.getOrDefault("approval", "").trim();
        boolean approved = p.ownerApproved();
        if (!approved && proof.isEmpty()) {
            return page(200, c, p, id, "还没有在服务器后台执行 /aibot remote approve " + p.confirm() + "。执行后再点“继续”。");
        }
        if (!approved) {
            if (!hooks.ownerProof(proof)) {
                hooks.onFailure("authorize");
                int attempts = p.attempts() + 1;
                if (attempts >= PENDING_ATTEMPTS) {
                    pending.remove(id);
                    return errorPage(401, "令牌错误次数太多，这个授权请求已作废。请回到原应用重新连接。");
                }
                pending.put(id, new Pending(p.clientId(), p.confirm(), p.redirectUri(), p.redirectGiven(), p.challenge(), p.state(), p.source(),
                        p.created(), p.expires(), attempts, false));
                return page(401, c, pending.get(id), id, "令牌不对，请重新输入（还可以试 " + (PENDING_ATTEMPTS - attempts) + " 次），或改用后台批准。");
            }
        }
        BotConfig.OAuth o = cfg.get().remote.oauth;
        if (!c.used() && authorizedCount() >= o.maxClients) {
            return errorPage(409, "已授权的客户端达到上限（" + o.maxClients + " 个）。请先在服务器后台用 /aibot remote clients 查看、"
                    + "/aibot remote revoke <client_id> 吊销不用的。");
        }
        pending.remove(id);
        String code = random(32);
        if (codes.size() > 500) codes.values().removeIf(x -> x.expires() < now);
        codes.put(sha(code), new Code(c.id(), p.redirectUri(), p.redirectGiven(), p.challenge(), now + CODE_MS, now));
        log.info("remote", "oauth.approved", "服主批准了 OAuth 客户端：" + label(c) + (approved ? "（后台确认号）" : "（固定令牌）"), null,
                JsonlLog.data("client_id", c.id(), "client", source));
        StringBuilder loc = new StringBuilder(p.redirectUri()).append(p.redirectUri().contains("?") ? '&' : '?').append("code=").append(enc(code));
        if (p.state() != null) loc.append("&state=").append(enc(p.state()));
        loc.append("&iss=").append(enc(base()));
        return new MiniHttp.Response(302, null, new byte[0], Map.of("Location", loc.toString(), "Cache-Control", "no-store", "Referrer-Policy", "no-referrer"));
    }

    private int authorizedCount() {
        int n = 0;
        for (Client c : clients.values()) if (c.used()) n++;
        return n;
    }

    // ———————————————————— 令牌端点 ————————————————————

    synchronized MiniHttp.Response token(MiniHttp.Request req, String source, Hooks hooks) {
        long now = System.currentTimeMillis();
        Map<String, String> f = parseForm(new String(req.body(), StandardCharsets.UTF_8));
        String clientId = f.get("client_id");
        String secret = f.get("client_secret");
        String basic = req.header("authorization");
        if (basic != null && basic.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String dec = new String(Base64.getDecoder().decode(basic.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = dec.indexOf(':');
                if (colon > 0) {
                    clientId = URLDecoder.decode(dec.substring(0, colon), StandardCharsets.UTF_8);
                    secret = URLDecoder.decode(dec.substring(colon + 1), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ignored) {
                // 下面按客户端认证失败处理
            }
        }
        Client c = clientId == null ? null : clients.get(clientId);
        if (c == null) {
            hooks.onFailure("token");
            return oauthError(401, "invalid_client", "unknown client");
        }
        if (c.secretHash() != null && (secret == null || !MessageDigest.isEqual(sha(secret).getBytes(StandardCharsets.US_ASCII),
                c.secretHash().getBytes(StandardCharsets.US_ASCII)))) {
            hooks.onFailure("token");
            return oauthError(401, "invalid_client", "client authentication failed");
        }
        String grant = f.getOrDefault("grant_type", "");
        if (grant.equals("authorization_code")) {
            String code = f.getOrDefault("code", "");
            Code k = codes.remove(sha(code)); // 先删：一次性，后面任何一步失败都作废
            String verifier = f.getOrDefault("code_verifier", "");
            String redirect = f.get("redirect_uri");
            // 授权请求里带了 redirect_uri，换令牌时必须带同一个；当时没带（只注册了一个，默认用它），这里可以不带，带了也必须一致
            boolean redirectOk = k != null && (redirect == null ? !k.redirectGiven() : k.redirectUri().equals(redirect));
            if (k == null || k.expires() < now || !k.clientId().equals(c.id()) || !redirectOk
                    || !verifier.matches("[A-Za-z0-9._~-]{43,128}")
                    || !MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII), k.challenge().getBytes(StandardCharsets.US_ASCII))) {
                hooks.onFailure("token");
                return oauthError(400, "invalid_grant", "invalid, expired or already used authorization code");
            }
            log.info("remote", "oauth.token_issued", "OAuth 客户端拿到令牌：" + label(c), null, JsonlLog.data("client_id", c.id(), "client", source));
            return issue(c, now, k.grantStart(), null);
        }
        if (grant.equals("refresh_token")) {
            String h = sha(f.getOrDefault("refresh_token", ""));
            Tok t = refresh.remove(h);
            if (t == null) {
                Rotated r = rotated.get(h);
                replays.values().removeIf(x -> now - x.at() > REFRESH_RETRY_GRACE_MS);
                Replay rp = replays.get(h);
                if (r != null && rp != null && !r.poisoned() && rp.clientId().equals(c.id()) && now - r.at() <= REFRESH_RETRY_GRACE_MS
                        && refresh.containsKey(r.child())) {
                    // 宽限期内、上次换出来的那一对还有效：原样再给一次（网络重试和多进程并发刷新都拿到同一对，谁都不会被踢）
                    log.info("remote", "oauth.refresh_replay", "宽限期内重复刷新，返回同一对令牌：" + label(c), null, JsonlLog.data("client_id", c.id(), "client", source));
                    return noStore(new MiniHttp.Response(200, "application/json", rp.body().getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                if (r != null && !r.poisoned() && r.clientId().equals(c.id()) && now - r.at() <= REFRESH_RETRY_GRACE_MS) {
                    // 刚换掉不到 30 秒又来：多半是上一次的响应在网络上丢了、客户端重试。作废上次签发的那一对（refresh 和配对的 access 都作废），
                    // 重新签发；被作废的那张 refresh 记成 poisoned——如果它其实没丢（有人重放了旧 token 抢先拿到新的一对），
                    // 持有它的一方之后一用就会触发"全部吊销"，攻击者和被踢的服主谁都留不下，而且日志里有告警。
                    Tok child = refresh.remove(r.child());
                    if (child != null) {
                        String acc = pairAccess.remove(r.child());
                        if (acc != null) access.remove(acc);
                        rotated.put(r.child(), new Rotated(c.id(), now, "", r.grantStart(), true));
                        return issue(c, now, r.grantStart(), h);
                    }
                }
                if (r != null) {
                    // 已经换掉的旧 refresh token 过了宽限期又被拿来用：令牌可能被盗，收回这个客户端的全部令牌
                    revokeTokens(r.clientId());
                    save();
                    log.warn("remote", "oauth.refresh_reuse", "检测到旧 refresh token 被重复使用，已吊销该客户端的全部令牌", null,
                            JsonlLog.data("client_id", r.clientId(), "client", source));
                }
                hooks.onFailure("token");
                return oauthError(400, "invalid_grant", "invalid refresh token");
            }
            long maxMs = cfg.get().remote.oauth.refreshMaxDays * 86_400_000L;
            if (!t.clientId().equals(c.id()) || t.expires() < now || now - t.grantStart() > maxMs) {
                save();
                hooks.onFailure("token");
                return oauthError(400, "invalid_grant", now - t.grantStart() > maxMs ? "authorization too old, please re-authorize" : "invalid refresh token");
            }
            return issue(c, now, t.grantStart(), h);
        }
        return oauthError(400, "unsupported_grant_type", "unsupported grant_type");
    }

    /** 签发一对令牌；rotatedFrom 不为空时记下"这个旧 refresh 换成了哪个新 refresh"。refresh 到期不超过首次授权 + refresh_max_days。 */
    private MiniHttp.Response issue(Client c, long now, long grantStart, String rotatedFrom) {
        BotConfig.OAuth o = cfg.get().remote.oauth;
        String at = random(32), rt = random(32);
        long atMs = o.accessTokenMinutes * 60_000L;
        access.values().removeIf(t -> t.expires() < now);
        access.put(sha(at), new Tok(c.id(), now + atMs, grantStart));
        long rtExp = Math.min(now + o.refreshTokenDays * 86_400_000L, grantStart + o.refreshMaxDays * 86_400_000L);
        refresh.put(sha(rt), new Tok(c.id(), rtExp, grantStart));
        pairAccess.put(sha(rt), sha(at));
        pairAccess.keySet().retainAll(refresh.keySet());
        if (rotatedFrom != null) {
            Rotated prev = rotated.remove(rotatedFrom);
            // 宽限期内的重试沿用第一次换掉的时间，宽限期不会被一直续下去
            rotated.put(rotatedFrom, new Rotated(c.id(), prev != null ? prev.at() : now, sha(rt), grantStart, false));
            trimRotated(now);
        }
        clients.put(c.id(), new Client(c.id(), c.name(), c.redirectUris(), c.authMethod(), c.secretHash(), c.created(), now, c.source()));
        save();
        JsonObject r = new JsonObject();
        r.addProperty("access_token", at);
        r.addProperty("token_type", "Bearer");
        r.addProperty("expires_in", atMs / 1000);
        r.addProperty("refresh_token", rt);
        r.addProperty("scope", SCOPE);
        if (rotatedFrom != null) replays.put(rotatedFrom, new Replay(c.id(), r.toString(), now));
        return noStore(json(200, r));
    }

    private void trimRotated(long now) {
        long maxMs = cfg.get().remote.oauth.refreshMaxDays * 86_400_000L;
        rotated.values().removeIf(r -> now - r.grantStart() > maxMs);
        while (rotated.size() > ROTATED_MAX) {
            Iterator<String> it = rotated.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** /mcp 请求带的 Bearer 是否是有效的 OAuth access token；是就返回 client_id。按哈希查表，不做逐字比较。 */
    public synchronized String validAccess(String bearer) {
        if (bearer == null || bearer.isEmpty() || !enabled()) return null;
        String h = sha(bearer);
        Tok t = access.get(h);
        if (t == null) return null;
        if (t.expires() < System.currentTimeMillis() || !clients.containsKey(t.clientId())) {
            access.remove(h);
            return null;
        }
        return t.clientId();
    }

    /** 这个 client_id 还在（没被吊销）：推送订阅投递前复查用。 */
    public synchronized boolean knownClient(String clientId) {
        return clients.containsKey(clientId);
    }

    public synchronized String clientLabel(String clientId) {
        Client c = clients.get(clientId);
        return c == null ? clientId : label(c);
    }

    // ———————————————————— 服主操作（/aibot remote …） ————————————————————

    /** 批准一个待批请求（确认号显示在批准页上）。 */
    public synchronized String approve(String confirm) {
        long now = System.currentTimeMillis();
        String want = confirm.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            Pending p = e.getValue();
            if (!p.confirm().equals(want)) continue;
            if (p.expires() < now) return "确认号 " + want + " 已过期，请回到原应用重新连接";
            Client c = clients.get(p.clientId());
            if (c == null) return "这个请求的客户端已不存在";
            e.setValue(new Pending(p.clientId(), p.confirm(), p.redirectUri(), p.redirectGiven(), p.challenge(), p.state(), p.source(),
                    p.created(), p.expires(), p.attempts(), true));
            log.info("remote", "oauth.owner_approved", "服主在后台批准了授权请求 " + want + "：" + label(c), null,
                    JsonlLog.data("client_id", c.id(), "client", p.source()));
            return "已批准 " + want + "：" + label(c) + "，批准后跳转到 " + URI.create(p.redirectUri()).getHost()
                    + "。回到批准页点“继续”即可（请确认页面上的确认号也是 " + want + "）。";
        }
        return "没有确认号为 " + want + " 的待批请求（可能输错或已过期）。不带参数执行 /aibot remote approve 可以列出待批请求。";
    }

    public synchronized String describePending() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expires() < now);
        if (pending.isEmpty()) return "没有待批的 OAuth 授权请求。请先在应用里发起连接，批准页上会显示确认号。";
        StringBuilder sb = new StringBuilder("待批的 OAuth 授权请求（核对批准页上的确认号后执行 /aibot remote approve <确认号>）：");
        for (Pending p : pending.values()) {
            Client c = clients.get(p.clientId());
            sb.append("\n").append(p.confirm()).append("  ").append(c == null ? p.clientId() : label(c)).append("  跳转到 ")
                    .append(URI.create(p.redirectUri()).getHost()).append("  来源 ").append(p.source()).append("  ")
                    .append((now - p.created()) / 60_000).append(" 分钟前").append(p.ownerApproved() ? "  （已批准，等页面点继续）" : "");
        }
        return sb.toString();
    }

    public synchronized String describeClients() {
        if (clients.isEmpty()) return "OAuth 客户端：没有";
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("OAuth 客户端（" + clients.size() + " 个）：");
        for (Client c : clients.values()) {
            long live = refresh.values().stream().filter(t -> t.clientId().equals(c.id()) && t.expires() >= now).count();
            sb.append("\n").append(c.id()).append("  ").append(c.name().isEmpty() ? "（未命名）" : c.name()).append("  回调 ").append(hosts(c.redirectUris()))
                    .append("  注册于 ").append(Text.time(c.created()).substring(0, 16)).append("（来源 ").append(c.source()).append("）")
                    .append(c.used() ? "  最近拿令牌 " + Text.time(c.lastUsed()).substring(0, 16) : "  从没拿到令牌")
                    .append(live > 0 ? "  有效授权 " + live : "");
        }
        return sb.toString();
    }

    public synchronized String revoke(String clientId) {
        Client c = clients.remove(clientId);
        if (c == null) return "没有这个客户端：" + clientId;
        revokeTokens(clientId);
        save();
        log.info("remote", "oauth.revoke", "吊销 OAuth 客户端：" + label(c), null, JsonlLog.data("client_id", clientId));
        return "已吊销 " + label(c) + " 及其全部令牌";
    }

    public synchronized String revokeAll() {
        int n = clients.size();
        clients.clear();
        refresh.clear();
        access.clear();
        rotated.clear();
        codes.clear();
        pending.clear();
        save();
        log.info("remote", "oauth.revoke_all", "吊销全部 OAuth 客户端和令牌", null, JsonlLog.data("clients", n));
        return "已吊销全部 " + n + " 个 OAuth 客户端及其令牌、未用的授权码和待批请求。固定令牌不受影响（要换固定令牌请删除 remote-token.txt 后 /aibot reload）。";
    }

    private void revokeTokens(String clientId) {
        refresh.values().removeIf(t -> t.clientId().equals(clientId));
        access.values().removeIf(t -> t.clientId().equals(clientId));
        codes.values().removeIf(t -> t.clientId().equals(clientId));
        pending.values().removeIf(t -> t.clientId().equals(clientId));
        rotated.values().removeIf(t -> t.clientId().equals(clientId));
        pairAccess.keySet().retainAll(refresh.keySet());
    }

    // ———————————————————— 持久化 ————————————————————

    private void load() {
        if (!Files.isRegularFile(stateFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
            long now = System.currentTimeMillis();
            if (o.has("clients")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("clients").entrySet()) {
                    JsonObject c = e.getValue().getAsJsonObject();
                    List<String> uris = new ArrayList<>();
                    c.getAsJsonArray("redirect_uris").forEach(u -> uris.add(u.getAsString()));
                    clients.put(e.getKey(), new Client(e.getKey(), str(c, "name", ""), List.copyOf(uris), str(c, "auth_method", "none"),
                            c.has("secret_sha256") && !c.get("secret_sha256").isJsonNull() ? c.get("secret_sha256").getAsString() : null,
                            c.get("created").getAsLong(), c.get("last_used").getAsLong(), str(c, "source", "")));
                }
            }
            if (o.has("refresh_tokens")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("refresh_tokens").entrySet()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    long exp = t.get("expires").getAsLong();
                    String cid = t.get("client_id").getAsString();
                    long start = t.has("grant_start") ? t.get("grant_start").getAsLong() : now; // 1.0.0.10 的文件没有这个字段：从现在算
                    if (exp >= now && clients.containsKey(cid)) refresh.put(e.getKey(), new Tok(cid, exp, start));
                }
            }
            if (o.has("rotated")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("rotated").entrySet()) {
                    JsonObject t = e.getValue().getAsJsonObject();
                    String cid = t.get("client_id").getAsString();
                    if (clients.containsKey(cid)) {
                        boolean poisoned = t.has("poisoned") && t.get("poisoned").getAsBoolean(); // 1.0.0.13 的文件没有这个字段
                        rotated.put(e.getKey(), new Rotated(cid, t.get("at").getAsLong(), t.get("child").getAsString(), t.get("grant_start").getAsLong(), poisoned));
                    }
                }
            }
            purgeUnusedClients(now);
        } catch (Exception e) {
            // 文件坏了：不信任其中任何内容（等于全部吊销），记一条错误，客户端重新授权即可；坏文件改名保留（不被下次保存覆盖）
            io.github.aftsakee.aiadmin.util.JsonFiles.quarantine(stateFile, e.getClass().getSimpleName());
            log.error("remote", "oauth.state_unreadable", "OAuth 状态文件读不了，已忽略（所有 OAuth 客户端需要重新授权）：" + e.getMessage(), null, JsonlLog.data());
            clients.clear();
            refresh.clear();
            rotated.clear();
        }
    }

    private void save() {
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 2);
        JsonObject cs = new JsonObject();
        for (Client c : clients.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", c.name());
            x.add("redirect_uris", arr(c.redirectUris().toArray(new String[0])));
            x.addProperty("auth_method", c.authMethod());
            x.addProperty("secret_sha256", c.secretHash());
            x.addProperty("created", c.created());
            x.addProperty("last_used", c.lastUsed());
            x.addProperty("source", c.source());
            cs.add(c.id(), x);
        }
        o.add("clients", cs);
        JsonObject rs = new JsonObject();
        long now = System.currentTimeMillis();
        refresh.values().removeIf(t -> t.expires() < now);
        for (Map.Entry<String, Tok> e : refresh.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("client_id", e.getValue().clientId());
            x.addProperty("expires", e.getValue().expires());
            x.addProperty("grant_start", e.getValue().grantStart());
            rs.add(e.getKey(), x);
        }
        o.add("refresh_tokens", rs);
        JsonObject ro = new JsonObject();
        for (Map.Entry<String, Rotated> e : rotated.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("client_id", e.getValue().clientId());
            x.addProperty("at", e.getValue().at());
            x.addProperty("child", e.getValue().child());
            x.addProperty("grant_start", e.getValue().grantStart());
            if (e.getValue().poisoned()) x.addProperty("poisoned", true);
            ro.add(e.getKey(), x);
        }
        o.add("rotated", ro);
        try {
            writePrivate(stateFile, o.toString());
        } catch (IOException e) {
            log.error("remote", "oauth.save_failed", "OAuth 状态文件写入失败：" + e.getMessage(), null, JsonlLog.data());
        }
    }

    /**
     * 原子写入只给属主和属组读写（660）的文件：临时文件在创建时就带权限（不是先建后改，没有可被别人读到的窗口），写完再原子替换。
     * Windows 没有 POSIX 权限，按普通文件写。
     */
    static void writePrivate(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve("." + target.getFileName() + "." + random(6) + ".tmp");
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        try {
            if (posix) Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw----")));
            else Files.createFile(tmp);
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ———————————————————— 页面 ————————————————————

    private MiniHttp.Response page(int status, Client c, Pending p, String requestId, String error) {
        String host = URI.create(p.redirectUri()).getHost();
        String name = c.name().isEmpty() ? c.id() : c.name();
        String html = "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>授权远程管理</title><style>"
                + "body{font-family:system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 16px;line-height:1.6;color:#222;background:#fff}"
                + ".box{border:1px solid #ccc;border-radius:8px;padding:16px 20px}.warn{background:#fff4e5;border:1px solid #f0b46c;padding:8px 12px;border-radius:6px}"
                + ".err{color:#b00020;font-weight:600}input[type=password]{width:100%;padding:8px;font-size:16px;box-sizing:border-box}"
                + "button{padding:8px 18px;font-size:15px;margin-right:8px}code{background:#f2f2f2;padding:1px 4px;border-radius:4px}"
                + ".code{font-size:28px;font-weight:700;letter-spacing:4px}.meta{color:#666;font-size:14px}"
                + "@media(prefers-color-scheme:dark){body{background:#1b1b1b;color:#ddd}.box{border-color:#555}.warn{background:#3a2a12;border-color:#8a5a1a}"
                + "code{background:#333}.meta{color:#aaa}}"
                + "</style></head><body><h2>授权远程管理 Minecraft 服务器</h2><div class=\"box\">"
                + "<p>应用 <b>" + esc(name) + "</b> 请求管理这台服务器（读取服务器信息" + (cfg.get().remote.allowActions ? "、执行后台指令、在游戏里说话" : "，只读") + "）。</p>"
                + "<p class=\"meta\">客户端 " + esc(c.id()) + "，注册于 " + esc(Text.time(c.created()).substring(0, 16)) + "（来源 " + esc(c.source())
                + "）；本次请求来源 " + esc(p.source()) + "。</p>"
                + "<p class=\"warn\">批准后会跳转到 <b>" + esc(host) + "</b>。如果这不是你正在连接的应用，请点“拒绝”。</p>"
                + (error == null ? "" : "<p class=\"err\">" + esc(error) + "</p>")
                + "<p>确认号：<span class=\"code\">" + esc(p.confirm()) + "</span></p>"
                + "<p>在服务器后台（控制台或 RCON）执行 <code>/aibot remote approve " + esc(p.confirm()) + "</code>，然后点“继续”。"
                + "或者在下面输入远程管理令牌（remote-token.txt）直接批准。</p>"
                + "<form method=\"post\" action=\"/authorize\"><input type=\"hidden\" name=\"request\" value=\"" + esc(requestId) + "\">"
                + "<p><input type=\"password\" name=\"approval\" autocomplete=\"off\" placeholder=\"远程管理令牌（用后台批准时留空）\"></p>"
                + "<p><button type=\"submit\" name=\"decision\" value=\"approve\">继续</button>"
                + "<button type=\"submit\" name=\"decision\" value=\"deny\">拒绝</button></p></form></div></body></html>";
        String csp = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' " + originOf(p.redirectUri())
                + "; frame-ancestors 'none'; base-uri 'none'";
        return new MiniHttp.Response(status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), secureHeaders(csp));
    }

    private static MiniHttp.Response errorPage(int status, String msg) {
        String html = "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\"><title>授权失败</title></head><body style=\"font-family:system-ui,sans-serif;"
                + "max-width:560px;margin:40px auto;padding:0 16px\"><h2>授权失败</h2><p>" + esc(msg) + "</p></body></html>";
        return new MiniHttp.Response(status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8),
                secureHeaders("default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
    }

    private static Map<String, String> secureHeaders(String csp) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Security-Policy", csp);
        h.put("X-Frame-Options", "DENY");
        h.put("X-Content-Type-Options", "nosniff");
        // same-origin：同站提交表单时浏览器带真实 Origin（no-referrer 会变成 Origin: null 被上面的校验拒绝）；
        // 对外站点仍不发 Referer。跳回客户端的 302 单独用 no-referrer，授权码不会经 Referer 外泄
        h.put("Referrer-Policy", "same-origin");
        h.put("Cache-Control", "no-store");
        return h;
    }

    private MiniHttp.Response redirectError(String redirect, String error, String state) {
        StringBuilder loc = new StringBuilder(redirect).append(redirect.contains("?") ? '&' : '?').append("error=").append(enc(error));
        if (state != null) loc.append("&state=").append(enc(state));
        loc.append("&iss=").append(enc(base()));
        return new MiniHttp.Response(302, null, new byte[0], Map.of("Location", loc.toString(), "Cache-Control", "no-store"));
    }

    // ———————————————————— 小工具 ————————————————————

    private boolean resourceMatches(String res) {
        String r = res.endsWith("/") ? res.substring(0, res.length() - 1) : res;
        return r.equals(resource()) || r.equals(base());
    }

    static String originOf(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "");
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                m.putIfAbsent(k, v); // 重复参数只认第一个
            } catch (IllegalArgumentException ignored) {
                // 编码坏了的参数忽略
            }
        }
        return m;
    }

    private static boolean subset(JsonObject o, String key, Set<String> allowed) {
        if (!o.has(key)) return true;
        if (!o.get(key).isJsonArray()) return false;
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() > 10) return false;
        for (JsonElement e : a) if (!e.isJsonPrimitive() || !allowed.contains(e.getAsString())) return false;
        return true;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static String cleanName(String s) {
        String t = s.length() > 200 ? s.substring(0, 200) : s;
        t = t.replaceAll("[\\p{Cntrl}]", "").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    private static String label(Client c) {
        return (c.name().isEmpty() ? "（未命名）" : c.name()) + " [" + c.id() + "]";
    }

    private static String hosts(List<String> uris) {
        List<String> h = new ArrayList<>();
        for (String u : uris) {
            String x = URI.create(u).getHost();
            if (!h.contains(x)) h.add(x);
        }
        return String.join(",", h);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(ch);
            }
        }
        return b.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonArray arr(String... xs) {
        JsonArray a = new JsonArray();
        for (String x : xs) a.add(x);
        return a;
    }

    private static MiniHttp.Response json(int status, JsonObject o) {
        return MiniHttp.Response.json(status, o.toString());
    }

    private static MiniHttp.Response noStore(MiniHttp.Response r) {
        Map<String, String> h = new LinkedHashMap<>(r.headers());
        h.put("Cache-Control", "no-store");
        h.put("Pragma", "no-cache");
        return new MiniHttp.Response(r.status(), r.contentType(), r.body(), h);
    }

    private static MiniHttp.Response oauthError(int status, String error, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("error", error);
        o.addProperty("error_description", desc);
        return noStore(json(status, o));
    }
}
