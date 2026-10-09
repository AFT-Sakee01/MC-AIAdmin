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
 * OpenAI Chat Completions 协议：GPT、DeepSeek、各种中转站。POST {base_url}/chat/completions。
 * DeepSeek 思考模式在工具循环里要求把 reasoning_content 原样带回，所以助手消息里有这个字段就保留。
 */
final class OpenAiSession extends BaseSession {
    private final JsonArray messages = new JsonArray();

    OpenAiSession(ProviderConfig cfg, List<ToolSpec> tools, HttpTransport http) throws LlmException {
        super(cfg, tools, http);
    }

    @Override
    String defaultAuthStyle() {
        return "bearer";
    }

    @Override
    public Reply start(String system, String user) throws LlmException {
        messages.add(msg("system", system));
        messages.add(msg("user", user));
        return call();
    }

    @Override
    public Reply submit(List<ToolResult> results) throws LlmException {
        for (ToolResult r : results) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "tool");
            m.addProperty("tool_call_id", r.call().id());
            m.addProperty("content", r.isError() ? "错误：" + r.content() : r.content());
            messages.add(m);
        }
        return call();
    }

    private Reply call() throws LlmException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.add("messages", messages.deepCopy());
        JsonArray ts = new JsonArray();
        for (ToolSpec t : tools) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", t.parameters());
            JsonObject w = new JsonObject();
            w.addProperty("type", "function");
            w.add("function", fn);
            ts.add(w);
        }
        if (!ts.isEmpty()) {
            body.add("tools", ts);
            body.addProperty("tool_choice", "auto");
        }
        if (cfg.maxTokens > 0) body.addProperty(cfg.maxTokensField, cfg.maxTokens);
        if (cfg.temperature != null) body.addProperty("temperature", cfg.temperature);
        JsonObject resp = post(join(cfg.baseUrl, null, "/chat/completions"), body);

        JsonArray choices = arr(resp, "choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) throw new LlmException("回复里没有 choices：" + snippet(resp.toString()), 200);
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject m = obj(choice, "message");
        if (m == null) throw new LlmException("回复里没有 message", 200);

        String text = contentText(m.get("content"));
        List<ToolCall> calls = new ArrayList<>();
        JsonArray tcs = new JsonArray(); // 只保留能解析的调用：历史里每个 tool_call 都必须有对应的 tool 回复，否则下一次请求 400
        for (JsonElement e : arr(m, "tool_calls")) {
            if (!e.isJsonObject()) continue;
            JsonObject tc = e.getAsJsonObject();
            JsonObject fn = obj(tc, "function");
            if (fn == null || str(tc, "id") == null || str(fn, "name") == null) continue;
            tcs.add(tc);
            calls.add(toolCall(str(tc, "id"), str(fn, "name"), fn.get("arguments")));
        }

        // 历史里只保留协议字段（有的中转会回多余字段，原样回传可能被拒）
        JsonObject hist = new JsonObject();
        hist.addProperty("role", "assistant");
        hist.addProperty("content", text == null ? "" : text);
        if (!tcs.isEmpty()) hist.add("tool_calls", tcs.deepCopy());
        if (m.has("reasoning_content") && !m.get("reasoning_content").isJsonNull()) hist.add("reasoning_content", m.get("reasoning_content"));
        messages.add(hist);

        JsonObject u = obj(resp, "usage");
        return new Reply(text == null ? "" : text, calls, new Llm.Usage(num(u, "prompt_tokens"), num(u, "completion_tokens")),
                str(choice, "finish_reason"));
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** content 一般是字符串；个别中转返回 [{type:text,text}] 数组。 */
    private static String contentText(JsonElement c) {
        if (c == null || c.isJsonNull()) return null;
        if (c.isJsonPrimitive()) return c.getAsString();
        if (c.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : c.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("text")) sb.append(e.getAsJsonObject().get("text").getAsString());
            }
            return sb.toString();
        }
        return c.toString();
    }
}
