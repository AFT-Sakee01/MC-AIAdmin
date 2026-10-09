package io.github.aftsakee.aiadmin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.llm.Llm.Reply;
import io.github.aftsakee.aiadmin.llm.Llm.ToolCall;
import io.github.aftsakee.aiadmin.llm.Llm.ToolResult;
import io.github.aftsakee.aiadmin.llm.Llm.ToolSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Claude Messages API：POST {base_url}/v1/messages（base_url 已以 /v1 结尾时不重复加）。
 * <p>约束：助手回复的 content 数组（含 thinking 块）必须原样追加进历史，后续请求只追加、不改写之前的轮次
 * ——新模型会校验思考块与历史是否一致，改写会 400 或丢失思考。所有工具结果放在同一条 user 消息里返回。
 * 不用官方 Java SDK：模组里不想打包 OkHttp/Jackson/Kotlin，而且要兼容只认原始 HTTP 的中转站。
 */
final class AnthropicSession extends BaseSession {
    static final String API_VERSION = "2023-06-01";
    private String system;
    private final JsonArray messages = new JsonArray();

    AnthropicSession(ProviderConfig cfg, List<ToolSpec> tools, HttpTransport http) throws LlmException {
        super(cfg, tools, http);
    }

    @Override
    String defaultAuthStyle() {
        return "x-api-key";
    }

    @Override
    java.util.Map<String, String> headers() {
        java.util.Map<String, String> h = super.headers();
        h.putIfAbsent("anthropic-version", API_VERSION);
        return h;
    }

    @Override
    public Reply start(String system, String user) throws LlmException {
        this.system = system;
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", user);
        messages.add(m);
        return call();
    }

    @Override
    public Reply submit(List<ToolResult> results) throws LlmException {
        JsonArray content = new JsonArray();
        for (ToolResult r : results) {
            JsonObject b = new JsonObject();
            b.addProperty("type", "tool_result");
            b.addProperty("tool_use_id", r.call().id());
            b.addProperty("content", r.content());
            if (r.isError()) b.addProperty("is_error", true);
            content.add(b);
        }
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.add("content", content);
        messages.add(m);
        return call();
    }

    private Reply call() throws LlmException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("max_tokens", cfg.maxTokens);
        body.addProperty("system", system);
        JsonArray ts = new JsonArray();
        for (ToolSpec t : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("input_schema", t.parameters());
            ts.add(o);
        }
        if (!ts.isEmpty()) body.add("tools", ts);
        if (cfg.temperature != null) body.addProperty("temperature", cfg.temperature);
        body.add("messages", messages.deepCopy());
        JsonObject resp = post(join(cfg.baseUrl, "/v1", "/messages"), body);
        // 和 OpenAI 协议一致：200 但不是正常回复（{"type":"error"}、没有 content）按失败处理，才会改用备用接入点，
        // 以前当成"空回复、运行成功"静默结束
        if ("error".equals(str(resp, "type")) || resp.has("error")) throw new LlmException("接口返回错误：" + snippet(resp.toString()), 200);
        if (!resp.has("content") || !resp.get("content").isJsonArray()) throw new LlmException("回复里没有 content：" + snippet(resp.toString()), 200);

        JsonArray content = arr(resp, "content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement e : content) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject();
            String type = str(b, "type");
            if ("text".equals(type)) text.append(str(b, "text"));
            else if ("tool_use".equals(type)) calls.add(toolCall(str(b, "id"), str(b, "name"), b.get("input")));
        }
        String stop = str(resp, "stop_reason");
        if ("refusal".equals(stop)) {
            // 安全分类器拒绝：不执行任何工具，按结束处理
            calls.clear();
            if (text.length() == 0) text.append("（模型拒绝了这个请求）");
        }
        JsonObject hist = new JsonObject();
        hist.addProperty("role", "assistant");
        hist.add("content", content.deepCopy());
        if (!content.isEmpty()) messages.add(hist);

        JsonObject u = obj(resp, "usage");
        long in = num(u, "input_tokens") + num(u, "cache_read_input_tokens") + num(u, "cache_creation_input_tokens");
        return new Reply(text.toString(), calls, new Llm.Usage(in, num(u, "output_tokens")), stop);
    }
}
