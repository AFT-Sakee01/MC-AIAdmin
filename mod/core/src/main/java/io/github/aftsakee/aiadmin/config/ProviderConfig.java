package io.github.aftsakee.aiadmin.config;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个模型接入点（config.json 的 providers.&lt;名字&gt;）。三种协议：
 * <ul>
 *   <li>{@code openai}：OpenAI Chat Completions（POST base_url/chat/completions）。GPT、DeepSeek 和绝大多数中转站都用它。</li>
 *   <li>{@code anthropic}：Claude Messages API（POST base_url/v1/messages）。官方或 Claude 中转。</li>
 *   <li>{@code antigravity}：Google Antigravity 托管代理的 Interactions API（POST base_url/v1beta/interactions）。</li>
 * </ul>
 * 密钥优先读 api_key，空则读环境变量 api_key_env；两者都不写进日志。
 */
public final class ProviderConfig {
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String ANTIGRAVITY = "antigravity";

    public String type = OPENAI;
    public String baseUrl = "";
    public String apiKey = "";
    public String apiKeyEnv = "";
    /** openai/anthropic：模型名；antigravity：代理 id（agent 字段，如 antigravity-preview-09-2026）。 */
    public String model = "";
    /** 每次回复的输出上限；0 = 不发送（openai 类型）。anthropic 必须 > 0。 */
    public int maxTokens = 0;
    /** openai 类型输出上限字段名：DeepSeek/大多数中转用 max_tokens，OpenAI 新模型用 max_completion_tokens。 */
    public String maxTokensField = "max_tokens";
    /** null = 不发送（Claude Opus 5.5 等新模型不接受采样参数，填了会 400）。 */
    public Double temperature = null;
    public int timeoutSeconds = 180;
    /** 鉴权头：default（按协议）/ bearer（Authorization: Bearer）/ x-api-key / x-goog-api-key。中转站要求不同时改这里。 */
    public String authStyle = "default";
    public Map<String, String> extraHeaders = new LinkedHashMap<>();
    /** 合并进请求体顶层的额外字段（例如 Claude 的 output_config、DeepSeek 的 thinking 开关）。 */
    public JsonObject extraBody = new JsonObject();

    // —— 提示词文件（相对 config/aiadmin/；留空 = prompts/<接入点名>.system.md / .custom.md，不存在时自动生成） ——
    public String systemPromptFile = "";
    public String customPromptFile = "";

    // —— 计费与余额 ——
    /** 每百万输入 / 输出 token 的单价（用于估算花费和 max_cost 额度；0 = 不计花费）。 */
    public double priceInputPerMillion = 0;
    public double priceOutputPerMillion = 0;
    public String currency = "";
    /** 余额接口：auto（base_url 是 api.deepseek.com 时用 deepseek）/ deepseek / newapi（one-api、new-api 类中转）/ none。 */
    public String balanceApi = "auto";
    // 余额的提醒线、停用线属于"规则"，在 rules.json 的 quota.provider_balance 里。

    // —— 仅 antigravity ——
    /** environment 字段：remote = Google 托管的新沙盒；也可填已有环境 id。 */
    public String environment = "remote";
    /** 额外开启的内置工具（code_execution / google_search / url_context）。默认全关：代理只能用本模组提供的工具。 */
    public List<String> builtinTools = new ArrayList<>();
    /** agent_config.model（可空 = 代理默认模型）。 */
    public String agentModel = "";
    /** agent_config.max_total_tokens（0 = 不限制，按服务端默认）。 */
    public int maxTotalTokens = 0;

    public String resolveKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            String v = System.getenv(apiKeyEnv.trim());
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    static ProviderConfig of(String type, String baseUrl, String model, String keyEnv) {
        ProviderConfig p = new ProviderConfig();
        p.type = type;
        p.baseUrl = baseUrl;
        p.model = model;
        p.apiKeyEnv = keyEnv;
        return p;
    }
}
