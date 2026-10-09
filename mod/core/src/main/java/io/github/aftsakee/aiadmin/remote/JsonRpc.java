package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * JSON-RPC 2.0 响应的两种外形（result / error），远程接口里只在这里构造。
 * <p>错误码：-32700 / -32600 / -32601 / -32602 是 JSON-RPC 标准码；-32000 到 -32019 是实现自留区（-32000 每分钟调用上限，
 * MCP Events 草案的 -32015 回调地址错误在 {@link McpEvents}）；-32020 请求头与请求体不一致、-32022 协议版本不支持是 2026-07-28 规范定的。
 * <p>线程：无状态、只有静态方法，MiniHttp 的多个处理线程可以同时调用。
 */
final class JsonRpc {
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    /** 超过每分钟调用上限（events/subscribe、unsubscribe；和 HTTP 429 + Retry-After 一起回，见 McpProtocol）。 */
    static final int RATE_LIMITED = -32000;
    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_VERSION = -32022;

    private JsonRpc() {}

    static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    static JsonObject error(JsonElement id, int code, String message) {
        return error(id, code, message, null);
    }

    /** id 为 null（解析失败、批量里不是对象的条目）时按规范回 "id": null。 */
    static JsonObject error(JsonElement id, int code, String message, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        if (data != null) e.add("data", data);
        o.add("error", e);
        return o;
    }
}
