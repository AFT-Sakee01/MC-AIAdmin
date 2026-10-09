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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Google Antigravity 托管代理（Gemini API 的 Interactions 接口）：POST {base_url}/v1beta/interactions。
 * <ul>
 *   <li>请求：agent = 代理 id（config 的 model），environment 必填（默认 remote），tools 只放本模组的函数工具
 *       ——显式传 tools 会替换掉默认开启的 code_execution / google_search / url_context，代理就只能通过我们的工具影响服务器。</li>
 *   <li>代理要调用函数时返回 status = requires_action，steps 里有 type = function_call 的项（id、name、arguments）；
 *       下一次请求带 previous_interaction_id 和同一个 environment，input 里放 type = function_result（name、call_id、result）。</li>
 *   <li>完成时 status = completed，最终文字在 output_text。in_progress 时每 2 秒 GET /v1beta/interactions/{id} 轮询。</li>
 * </ul>
 * 没有 system 字段，系统提示词拼在第一条输入前面。字段名按官方文档（2026-10）实现，解析时对 steps/outputs、id/call_id 都做了兼容。
 */
final class AntigravitySession extends BaseSession {
    private static final long POLL_MS = 2000;
    private String interactionId;
    private String environmentId;
    private final Set<String> answered = new HashSet<>();

    AntigravitySession(ProviderConfig cfg, List<ToolSpec> tools, HttpTransport http) throws LlmException {
        super(cfg, tools, http);
    }

    @Override
    String defaultAuthStyle() {
        return "x-goog-api-key";
    }

    private String url() {
        return join(cfg.baseUrl, "/v1beta", "/interactions");
    }

    @Override
    public Reply start(String system, String user) throws LlmException {
        JsonArray input = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", "【系统设定（必须遵守）】\n" + system + "\n\n【本次任务】\n" + user);
        input.add(t);
        JsonObject body = base();
        body.addProperty("environment", cfg.environment);
        body.add("input", input);
        return handle(post(url(), body));
    }

    @Override
    public Reply submit(List<ToolResult> results) throws LlmException {
        JsonArray input = new JsonArray();
        for (ToolResult r : results) {
            JsonObject fr = new JsonObject();
            fr.addProperty("type", "function_result");
            fr.addProperty("name", r.call().name());
            fr.addProperty("call_id", r.call().id());
            JsonArray res = new JsonArray();
            JsonObject txt = new JsonObject();
            txt.addProperty("type", "text");
            txt.addProperty("text", r.isError() ? "错误：" + r.content() : r.content());
            res.add(txt);
            fr.add("result", res);
            input.add(fr);
            answered.add(r.call().id());
        }
        JsonObject body = base();
        body.addProperty("previous_interaction_id", interactionId);
        body.addProperty("environment", environmentId != null ? environmentId : cfg.environment);
        body.add("input", input);
        return handle(post(url(), body));
    }

    private JsonObject base() {
        JsonObject body = new JsonObject();
        body.addProperty("agent", cfg.model);
        JsonArray ts = new JsonArray();
        for (String b : cfg.builtinTools) {
            JsonObject o = new JsonObject();
            o.addProperty("type", b);
            ts.add(o);
        }
        for (ToolSpec s : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "function");
            o.addProperty("name", s.name());
            o.addProperty("description", s.description());
            o.add("parameters", s.parameters());
            ts.add(o);
        }
        body.add("tools", ts);
        body.addProperty("store", true); // previous_interaction_id 需要服务端保存上一轮
        if (!cfg.agentModel.isBlank() || cfg.maxTotalTokens > 0) {
            JsonObject ac = new JsonObject();
            ac.addProperty("type", "antigravity");
            if (!cfg.agentModel.isBlank()) ac.addProperty("model", cfg.agentModel);
            if (cfg.maxTotalTokens > 0) ac.addProperty("max_total_tokens", cfg.maxTotalTokens);
            body.add("agent_config", ac);
        }
        return body;
    }

    private Reply handle(JsonObject resp) throws LlmException {
        long deadline = System.currentTimeMillis() + cfg.timeoutSeconds * 1000L;
        String status = str(resp, "status");
        while (status != null && (status.equals("in_progress") || status.equals("queued") || status.equals("running"))) {
            if (System.currentTimeMillis() > deadline) throw new LlmException("Antigravity 代理超时未完成", 0);
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("已取消", e);
            }
            resp = request("GET", url() + "/" + str(resp, "id"), null);
            status = str(resp, "status");
        }
        if (str(resp, "id") != null) interactionId = str(resp, "id");
        if (str(resp, "environment_id") != null) environmentId = str(resp, "environment_id");
        if (status == null && resp.has("error")) throw new LlmException("Antigravity 返回错误：" + snippet(String.valueOf(resp.get("error"))), 200);
        if (status != null && (status.equals("failed") || status.equals("cancelled") || status.equals("incomplete") && !hasCalls(resp))) {
            throw new LlmException("Antigravity 代理状态 " + status + "：" + snippet(String.valueOf(resp.get("error"))), 200);
        }

        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (String listKey : new String[] {"steps", "outputs"}) {
            for (JsonElement e : arr(resp, listKey)) {
                if (!e.isJsonObject()) continue;
                JsonObject s = e.getAsJsonObject();
                String type = str(s, "type");
                if ("function_call".equals(type)) {
                    String id = str(s, "id") != null ? str(s, "id") : str(s, "call_id");
                    if (id != null && !answered.contains(id) && calls.stream().noneMatch(c -> c.id().equals(id))) {
                        calls.add(toolCall(id, str(s, "name"), s.has("arguments") ? s.get("arguments") : s.get("args")));
                    }
                } else if ("text".equals(type) && str(s, "text") != null && str(resp, "output_text") == null) {
                    text.append(str(s, "text"));
                }
            }
        }
        if (str(resp, "output_text") != null) text.append(str(resp, "output_text"));
        if ("requires_action".equals(status) && calls.isEmpty()) {
            throw new LlmException("Antigravity 要求执行动作，但没找到未回复的 function_call：" + snippet(resp.toString()), 200);
        }
        if ("completed".equals(status)) calls.clear(); // 已完成就没有待执行的调用

        JsonObject u = obj(resp, "usage");
        return new Reply(text.toString(), calls,
                new Llm.Usage(num(u, "input_tokens", "total_input_tokens"), num(u, "output_tokens", "total_output_tokens")), status);
    }

    private static boolean hasCalls(JsonObject resp) {
        for (JsonElement e : arr(resp, "steps")) {
            if (e.isJsonObject() && "function_call".equals(str(e.getAsJsonObject(), "type"))) return true;
        }
        return false;
    }
}
