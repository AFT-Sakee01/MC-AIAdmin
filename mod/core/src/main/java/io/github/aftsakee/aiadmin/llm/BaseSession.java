package io.github.aftsakee.aiadmin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.llm.Llm.ToolSpec;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三种协议共用的部分：鉴权头、重试、JSON 小工具。 */
abstract class BaseSession implements Llm.Session {
    /**
     * 可重试的状态码。重试同一个模型请求没有副作用（工具是在本地执行的，不在请求里），代价只是多花一次 token，
     * 所以对限流/网关错误最多重试 2 次（共 3 次），间隔 2 s、6 s；4xx 里的参数/鉴权错误立即失败。
     */
    private static final Set<Integer> RETRY = Set.of(408, 425, 429, 500, 502, 503, 504, 529);
    private static final long[] BACKOFF_MS = {2000, 6000};
    /** 服务端用 Retry-After 要求等待时最多等这么久（再长就不等了，直接按失败处理，交给备用接入点或下次运行）。 */
    static final long MAX_RETRY_AFTER_MS = 30_000L;

    final ProviderConfig cfg;
    final List<ToolSpec> tools;
    final HttpTransport http;
    final String key;

    BaseSession(ProviderConfig cfg, List<ToolSpec> tools, HttpTransport http) throws LlmException {
        this.cfg = cfg;
        this.tools = tools;
        this.http = http;
        this.key = cfg.resolveKey();
        if (key.isEmpty()) throw new LlmException("没有配置 API key（api_key 或环境变量 api_key_env）", 0);
    }

    /** 协议默认的鉴权方式；auth_style 可以改。 */
    abstract String defaultAuthStyle();

    Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        String style = cfg.authStyle.equals("default") ? defaultAuthStyle() : cfg.authStyle;
        switch (style) {
            case "bearer" -> h.put("Authorization", "Bearer " + key);
            case "x-api-key" -> h.put("x-api-key", key);
            case "x-goog-api-key" -> h.put("x-goog-api-key", key);
            default -> throw new IllegalStateException(style);
        }
        h.putAll(cfg.extraHeaders);
        return h;
    }

    JsonObject post(String url, JsonObject body) throws LlmException {
        for (Map.Entry<String, JsonElement> e : cfg.extraBody.entrySet()) body.add(e.getKey(), e.getValue().deepCopy());
        return request("POST", url, body.toString());
    }

    JsonObject request(String method, String url, String body) throws LlmException {
        Duration timeout = Duration.ofSeconds(cfg.timeoutSeconds);
        LlmException last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (attempt > 0) sleep(BACKOFF_MS[attempt - 1]);
            try {
                HttpTransport.Response r = http.send(method, url, headers(), body, timeout);
                if (r.status() / 100 == 2) {
                    try {
                        return JsonParser.parseString(r.body()).getAsJsonObject();
                    } catch (RuntimeException e) {
                        throw new LlmException("返回的不是 JSON 对象：" + snippet(r.body()), r.status());
                    }
                }
                if (r.status() / 100 == 3) throw new LlmException("HTTP " + r.status() + "：接口要求跳转到别的地址（为了不把密钥带去别处，不跟随跳转；请把 base_url 改成最终地址）", r.status());
                last = new LlmException("HTTP " + r.status() + "：" + snippet(r.body()), r.status());
                if (!RETRY.contains(r.status())) throw last;
                if (r.retryAfterSeconds() != null && attempt < BACKOFF_MS.length) {
                    long wait = r.retryAfterSeconds() * 1000L;
                    if (wait > MAX_RETRY_AFTER_MS) throw last; // 要等太久：不占着单线程的代理干等
                    if (wait > BACKOFF_MS[attempt]) sleep(wait - BACKOFF_MS[attempt]); // 下一轮开头还会再睡 BACKOFF_MS[attempt]
                }
            } catch (java.net.http.HttpTimeoutException e) {
                // 超时不重试：单步最长就是接入点的 timeout_seconds（以前重试 2 次，最长要等 3 倍，远超 run_timeout_seconds，
                // 单线程的代理整个卡住；超时的请求对方可能已经计费，重试还会再花一次钱）
                throw new LlmException("请求超时（" + cfg.timeoutSeconds + " 秒没有回应）", e);
            } catch (IOException e) {
                last = new LlmException("网络错误：" + e.getClass().getSimpleName() + " " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("已取消", e);
            }
        }
        throw last;
    }

    private static void sleep(long ms) throws LlmException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("已取消", e);
        }
    }

    static String snippet(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /** base_url 末尾去掉 /，再按需补版本前缀：base 已经以 versionSeg 结尾就不重复加。 */
    static String join(String base, String versionSeg, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (versionSeg != null && !versionSeg.isEmpty() && !b.endsWith(versionSeg)) b += versionSeg;
        return b + path;
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static long num(JsonObject o, String... keys) {
        if (o == null) return 0;
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
        }
        return 0;
    }

    static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** 参数可能是 JSON 字符串（OpenAI）也可能是对象（Claude、Antigravity）。 */
    static Llm.ToolCall toolCall(String id, String name, JsonElement args) {
        if (args == null || args.isJsonNull()) return new Llm.ToolCall(id, name, new JsonObject(), null);
        if (args.isJsonObject()) return new Llm.ToolCall(id, name, args.getAsJsonObject(), null);
        if (!args.isJsonPrimitive()) return new Llm.ToolCall(id, name, new JsonObject(), "参数不是 JSON 对象");
        String s = args.getAsString();
        if (s.isBlank()) return new Llm.ToolCall(id, name, new JsonObject(), null);
        try {
            JsonElement p = JsonParser.parseString(s);
            if (p.isJsonObject()) return new Llm.ToolCall(id, name, p.getAsJsonObject(), null);
            return new Llm.ToolCall(id, name, new JsonObject(), "参数不是 JSON 对象");
        } catch (RuntimeException e) {
            return new Llm.ToolCall(id, name, new JsonObject(), "参数不是合法 JSON：" + snippet(s));
        }
    }
}
