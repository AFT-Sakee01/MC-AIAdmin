package io.github.aftsakee.aiadmin.llm;

import io.github.aftsakee.aiadmin.config.ProviderConfig;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.llm.Llm.ToolSpec;

import java.util.List;

/** 按 type 创建会话。 */
public final class Providers {
    private Providers() {}

    public static Llm.Session open(ProviderConfig cfg, List<ToolSpec> tools, HttpTransport http) throws LlmException {
        return switch (cfg.type) {
            case ProviderConfig.OPENAI -> new OpenAiSession(cfg, tools, http);
            case ProviderConfig.ANTHROPIC -> new AnthropicSession(cfg, tools, http);
            case ProviderConfig.ANTIGRAVITY -> new AntigravitySession(cfg, tools, http);
            default -> throw new LlmException("未知的接入类型：" + cfg.type, 0);
        };
    }
}
