package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 协议分发：已经通过 {@link RemoteGate}（来源、Origin、令牌）的 POST /mcp 请求。Streamable HTTP 只回 application/json
 * （不开 SSE 流，非 POST 回 405），JSON-RPC 2.0，两代协议并存：
 * <ul>
 *   <li>旧版（2025-11-25 及以前）：先 initialize 握手；允许批量，最多 {@link #MAX_BATCH} 条；未知方法 200 + -32601。</li>
 *   <li>新版（{@link #MODERN}，无握手、无会话）：每个请求在 params._meta 里带协议版本和能力；MCP-Protocol-Version / Mcp-Method / Mcp-Name
 *       请求头必须和请求体一致（规范要求：中间层按头路由、服务端按体执行，不一致就可能执行和请求头不同的工具），否则 400；
 *       结果带 resultType 和 serverInfo；未知方法 404；不能批量；请求头说新版、请求体却不带 _meta 也回 400（按旧版执行就绕过了一致性检查）。</li>
 * </ul>
 * tools/* 委托 {@link RemoteTools}，events/* 委托 {@link McpEvents}；initialize、server/discover、ping 在这里回答。
 * 不发会话 id（规范允许无状态服务端），每次 tools/call 用一个新的 RunContext。
 * <p>限流的两种外形按层次区分：tools/call 超过每分钟/每小时上限回 isError 工具结果（模型能看到原因，说明里写了多久后可重试，见 RemoteTools）；
 * events/subscribe、unsubscribe 不是工具调用，超过每分钟上限回 HTTP 429 + Retry-After，响应体是 JSON-RPC 错误 {@link JsonRpc#RATE_LIMITED}。
 * 旧版批量里某一条被限流时整批仍回 200、那一条带错误：同批别的条目可能已经执行，整批回 429 会让客户端重放。
 * <p>线程：被 MiniHttp 的多个处理线程同时调用。本类没有可变状态——events 每次从 McpServer 的 volatile 字段取（重启时会换实例，可能为 null），
 * 计数和锁都在 RemoteTools / McpEvents 里。
 */
final class McpProtocol {
    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    /** 新版（无握手）协议版本；旧版客户端走 initialize。 */
    static final String MODERN = "2026-07-28";
    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CAPS = "io.modelcontextprotocol/clientCapabilities";
    static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";
    static final String META_SERVER = "io.modelcontextprotocol/serverInfo";
    /** 旧版协议一次批量最多几条：每条 tools/list 都是完整工具表，1 MiB 请求体能塞上万条，结果能把服务端内存撑爆、把审计日志冲掉。 */
    static final int MAX_BATCH = 20;

    private final Supplier<BotConfig> cfg;
    private final JsonlLog log;
    private final String version;
    private final RemoteTools remoteTools;
    private final Supplier<McpEvents> events;

    McpProtocol(Supplier<BotConfig> cfg, JsonlLog log, String version, RemoteTools remoteTools, Supplier<McpEvents> events) {
        this.cfg = cfg;
        this.log = log;
        this.version = version;
        this.remoteTools = remoteTools;
        this.events = events;
    }

    /** 一条 JSON-RPC 消息的处理结果：HTTP 状态 + 响应体（null = 通知/客户端响应，回 202 无内容）+ 额外响应头（429 的 Retry-After）。 */
    record Reply(int status, JsonObject body, Map<String, String> headers) {
        Reply(int status, JsonObject body) {
            this(status, body, Map.of());
        }
    }

    /** 持有效令牌的 /mcp 请求（RemoteGate 已放行）。who = 审计日志里的来源（含 OAuth 客户端名），principal = 推送订阅的归属。 */
    MiniHttp.Response handle(MiniHttp.Request req, String who, String principal) {
        if (!req.method().equals("POST")) return MiniHttp.Response.empty(405, Map.of("Allow", "POST"));
        JsonElement body;
        try {
            body = JsonParser.parseString(new String(req.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return MiniHttp.Response.json(400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error").toString());
        }
        if (body.isJsonArray()) {
            // 批量只有旧版协议允许，并限制条数（见 MAX_BATCH）；新版协议的消息（带 _meta 协议版本）不能放进批量——
            // 批量里每条不会再核对 Mcp-Method/Mcp-Name 请求头，混进来就能执行和请求头不一致的工具
            JsonArray batch = body.getAsJsonArray();
            if (batch.size() > MAX_BATCH) return MiniHttp.Response.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Batch too large (max " + MAX_BATCH + ")").toString());
            if (MODERN.equals(headerTrim(req, "mcp-protocol-version"))) return batchNotAllowed();
            for (JsonElement e : batch) {
                if (e.isJsonObject() && isModern(e.getAsJsonObject())) return batchNotAllowed();
            }
            JsonArray out = new JsonArray();
            for (JsonElement e : batch) {
                // 每条的 HTTP 状态（含限流的 429）在批量里丢弃，只保留响应体：理由见类注释
                JsonObject res = e.isJsonObject() ? process(e.getAsJsonObject(), who, principal, req).body() : JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request");
                if (res != null) out.add(res);
            }
            return out.isEmpty() ? MiniHttp.Response.empty(202, Map.of()) : MiniHttp.Response.json(200, out.toString());
        }
        if (!body.isJsonObject()) return MiniHttp.Response.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request").toString());
        Reply res = process(body.getAsJsonObject(), who, principal, req);
        if (res.body() == null) return MiniHttp.Response.empty(202, Map.of());
        MiniHttp.Response json = MiniHttp.Response.json(res.status(), res.body().toString());
        return res.headers().isEmpty() ? json : new MiniHttp.Response(json.status(), json.contentType(), json.body(), res.headers());
    }

    private static MiniHttp.Response batchNotAllowed() {
        return MiniHttp.Response.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Batch not allowed in protocol " + MODERN).toString());
    }

    private static String headerTrim(MiniHttp.Request req, String name) {
        String v = req == null ? null : req.header(name);
        return v == null ? null : v.trim();
    }

    /** 消息体是否按新版协议发（params._meta 里带协议版本）。 */
    private static boolean isModern(JsonObject msg) {
        if (!msg.has("params") || !msg.get("params").isJsonObject()) return false;
        JsonObject p = msg.getAsJsonObject("params");
        return p.has("_meta") && p.get("_meta").isJsonObject() && p.getAsJsonObject("_meta").has(META_VERSION);
    }

    Reply process(JsonObject msg, String client, String principal, MiniHttp.Request req) {
        JsonElement id = msg.get("id");
        String method = msg.has("method") && msg.get("method").isJsonPrimitive() ? msg.get("method").getAsString() : null;
        if (method == null) {
            if (msg.has("result") || msg.has("error")) return new Reply(202, null); // 客户端发来的 JSON-RPC 响应：按规范忽略
            return id == null ? new Reply(202, null) : new Reply(200, JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Invalid Request"));
        }
        if (id == null || id.isJsonNull()) return new Reply(202, null); // 通知（notifications/initialized 等）：不回
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : null;
        boolean modern = meta != null && meta.has(META_VERSION);
        String clientInfo = null;
        if (!modern && MODERN.equals(headerTrim(req, "mcp-protocol-version"))) {
            // 请求头说是新版、请求体却不带 _meta：按旧版执行就绕过了下面的请求头一致性检查（中间层按头路由、服务端按体执行）
            badRequest(client, method, "header " + MODERN + " but body without _meta", null);
            return new Reply(400, JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Missing _meta " + META_VERSION + " (MCP-Protocol-Version header is " + MODERN + ")"));
        }
        if (modern) {
            // 新版（无握手）：每个请求自带协议版本；请求头必须和请求体一致（规范：中间层按头路由，服务端按体执行，不一致就拒绝）
            String requested = meta.get(META_VERSION).isJsonPrimitive() ? meta.get(META_VERSION).getAsString() : "";
            if (!MODERN.equals(requested)) {
                badRequest(client, method, "unsupported_version " + requested, clientInfo(meta));
                JsonObject data = new JsonObject();
                JsonArray supported = new JsonArray();
                supported.add(MODERN);
                data.add("supported", supported);
                data.addProperty("requested", requested);
                return new Reply(400, JsonRpc.error(id, JsonRpc.UNSUPPORTED_VERSION, "Unsupported protocol version", data));
            }
            String hv = req == null ? null : req.header("mcp-protocol-version");
            clientInfo = clientInfo(meta);
            if (hv == null || !hv.trim().equals(requested)) {
                badRequest(client, method, "header MCP-Protocol-Version=" + hv, clientInfo);
                return new Reply(400, JsonRpc.error(id, JsonRpc.HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version must equal _meta protocolVersion"));
            }
            String hm = req == null ? null : req.header("mcp-method");
            if (hm == null || !hm.trim().equals(method)) {
                badRequest(client, method, "header Mcp-Method=" + hm, clientInfo);
                return new Reply(400, JsonRpc.error(id, JsonRpc.HEADER_MISMATCH, "Header mismatch: Mcp-Method must equal method"));
            }
            if (method.equals("tools/call")) {
                String hn = decodeHeader(req == null ? null : req.header("mcp-name"));
                String bn = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
                if (hn == null || !hn.equals(bn)) {
                    badRequest(client, method, "header Mcp-Name=" + hn + " body=" + bn, clientInfo);
                    return new Reply(400, JsonRpc.error(id, JsonRpc.HEADER_MISMATCH, "Header mismatch: Mcp-Name must equal params.name"));
                }
            }
            if (!meta.has(META_CAPS) || !meta.get(META_CAPS).isJsonObject()) {
                badRequest(client, method, "missing clientCapabilities", clientInfo);
                return new Reply(400, JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Missing _meta " + META_CAPS));
            }
        }
        // 协议层方法也记一条（tools/call 在 callTool 里记）：排查客户端扫描时有没有走到 discover / events
        if (!method.equals("tools/call") && !method.equals("ping")) {
            log.info("remote", "remote.method", method + (modern ? "（新版协议）" : "（旧版协议）"), null,
                    JsonlLog.data("method", method, "client", client, "client_info", clientInfo, "modern", modern));
        }
        McpEvents ev = events.get();
        boolean eventWrite = method.equals("events/subscribe") || method.equals("events/unsubscribe");
        if (eventWrite && ev != null) {
            // 订阅/退订和 tools/call 共用每分钟上限（订阅会同步发出站验证请求）；这不是工具调用，按请求层限流回 429 + Retry-After
            long wait = remoteTools.takeCallSlot();
            if (wait > 0) {
                return new Reply(429, JsonRpc.error(id, JsonRpc.RATE_LIMITED, remoteTools.callLimitMessage(wait)), Map.of("Retry-After", Long.toString(wait)));
            }
        }
        JsonObject result;
        try {
            result = switch (method) {
                case "initialize" -> modern ? null : initialize(params);
                case "server/discover" -> discover();
                case "ping" -> new JsonObject();
                case "tools/list" -> remoteTools.toolsList(modern);
                case "tools/call" -> remoteTools.callTool(params, client, clientInfo);
                case "events/list" -> ev == null ? null : ev.list();
                case "events/subscribe" -> ev == null ? null : ev.subscribe(params, principal);
                case "events/unsubscribe" -> ev == null ? null : ev.unsubscribe(params, principal);
                default -> null;
            };
        } catch (McpEvents.RpcError e) {
            return new Reply(200, JsonRpc.error(id, e.code, e.getMessage(), e.data));
        }
        // 新版协议：未知方法是 404（和旧版 HTTP+SSE 服务端的 404 靠 JSON-RPC 错误体区分）
        if (result == null) {
            if (modern) badRequest(client, method, "method not found", clientInfo);
            return new Reply(modern ? 404 : 200, JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method));
        }
        if (modern) {
            result.addProperty("resultType", "complete");
            if (!result.has("_meta")) result.add("_meta", serverMeta());
        }
        return new Reply(200, JsonRpc.result(id, result));
    }

    private static String clientInfo(JsonObject meta) {
        if (meta == null || !meta.has(META_CLIENT) || !meta.get(META_CLIENT).isJsonObject()) return null;
        JsonObject ci = meta.getAsJsonObject(META_CLIENT);
        return Text.brief((ci.has("name") ? ci.get("name").getAsString() : "?") + "/" + (ci.has("version") ? ci.get("version").getAsString() : "?"), 80);
    }

    /** 新版协议请求被拒（400）：记一条，方便排查客户端为什么回退或报错。只有持有效令牌的请求能走到这里，量不大。 */
    private void badRequest(String client, String method, String why, String clientInfo) {
        log.warn("remote", "remote.bad_request", "新版协议请求被拒：" + method + "，" + why, null,
                JsonlLog.data("method", method, "reason", why, "client", client, "client_info", clientInfo));
    }

    /** Mcp-Name / Mcp-Param-* 请求头：非 ASCII 的值按 =?base64?…?= 编码。 */
    static String decodeHeader(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.startsWith("=?base64?") && t.endsWith("?=") && t.length() > 11) {
            try {
                return new String(Base64.getDecoder().decode(t.substring(9, t.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return t;
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive() ? params.get("protocolVersion").getAsString() : "";
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", VERSIONS.contains(requested) ? requested : VERSIONS.get(1));
        r.add("capabilities", capabilities(false));
        r.add("serverInfo", serverInfo());
        r.addProperty("instructions", instructions());
        return r;
    }

    /** server/discover（新版协议，旧版客户端调用也回答）：支持的版本、能力、身份；结果可缓存一小时。 */
    private JsonObject discover() {
        JsonObject r = new JsonObject();
        JsonArray v = new JsonArray();
        v.add(MODERN);
        r.add("supportedVersions", v);
        r.add("capabilities", capabilities(true));
        r.add("_meta", serverMeta());
        r.addProperty("instructions", instructions());
        r.addProperty("ttlMs", 3600_000);
        r.addProperty("cacheScope", "public");
        return r;
    }

    private static JsonObject capabilities(boolean withEvents) {
        JsonObject caps = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        caps.add("tools", toolsCap);
        if (withEvents) caps.add("events", new JsonObject()); // MCP Events（webhook 推送），见 McpEvents
        return caps;
    }

    private JsonObject serverInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("name", "aiadmin-minecraft");
        info.addProperty("title", "Minecraft AI 管理机器人");
        info.addProperty("version", version);
        return info;
    }

    private JsonObject serverMeta() {
        JsonObject m = new JsonObject();
        m.add(META_SERVER, serverInfo());
        return m;
    }

    private String instructions() {
        return "这是一个 Minecraft 服务器（" + cfg.get().botName + " 模组）的管理接口。get_/list_/read_ 开头的工具只读；"
                + "run_command 以后台控制台身份执行指令（受服主的黑名单/白名单限制，被拒绝时不要换写法绕过）；send_chat 在游戏里以机器人身份说话；"
                + "aibot_status / aibot_quota 查看机器人自身状态与额度。"
                + "工具表可能比你缓存的新：要用的工具不在你的列表里时，先 list_tools 查名字和参数，再用 call_read_tool（只读）或 call_action_tool（会改动服务器）调用，"
                + "检查和审计与直接调用相同。"
                + "云端值守（服主开了 remote.cloud 时）：先 get_bot_prompt 读规矩，然后循环调用 wait_events 取玩家聊天/事件/巡检，"
                + "用 reply_chat / run_command_for（带 item_id）处理，最后 complete_item；memory_read / memory_note 读写和本地 AI 共用的记忆。"
                + "不能一直挂着长轮询的客户端（ChatGPT、dots）改为订阅事件 " + McpEvents.EVENT_ITEM + "：收件箱有新消息时服务器会推送到你的回调地址，"
                + "被叫醒后再调用 wait_events 取走处理。所有调用都会写入审计日志。";
    }
}
