package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.List;

/**
 * 运行内上下文压缩：判断时机（{@link #due}）、把已有进展压成摘要（{@link #compress}）。
 * 方法 digest 不花 token；summarize 调模型写摘要，失败时退回 digest（压缩失败不能让运行失败）。
 */
public final class Compressor {
    /** 无工具的一次性调用：provider 为接入点名。由 AgentService 实现（顺便把 token 记进额度）。 */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(String provider, String instructions, String material, RunContext ctx) throws LlmException;
    }

    /** 一轮：模型说的话 + 它调用的工具及结果。 */
    public record Step(int step, String text, List<Llm.ToolResult> results) {}

    public static final Compressor OFF = new Compressor(null, null, null);

    private final BotConfig.Compression cfg;
    private final Prompts prompts;
    private final Summarizer summarizer;

    public Compressor(BotConfig.Compression cfg, Prompts prompts, Summarizer summarizer) {
        this.cfg = cfg;
        this.prompts = prompts;
        this.summarizer = summarizer;
    }

    /**
     * 是否该压缩。约束：每次运行最多 max_per_run 次；两次压缩至少隔 2 轮
     * （压缩后的第一轮上下文还没被模型重新计量，避免提示词本身就超阈值时每轮都压）。
     */
    public boolean due(int step, long lastInputTokens, long contextChars, int done, int lastCompressStep) {
        if (cfg == null || !cfg.enabled || done >= cfg.method.maxPerRun) return false;
        if (lastCompressStep > 0 && step - lastCompressStep < 2) return false;
        BotConfig.When w = cfg.when;
        return (w.inputTokensOver > 0 && lastInputTokens > w.inputTokensOver)
                || (w.contextCharsOver > 0 && contextChars > w.contextCharsOver)
                || (w.everySteps > 0 && step % w.everySteps == 0);
    }

    public String compress(List<Step> steps, RunContext ctx, JsonlLog log) {
        BotConfig.Method m = cfg.method;
        String digest = digest(steps, m.keepRecentSteps, m.oldResultChars, ctx.cfg.limits.toolResultMaxChars);
        if (!m.type.equals("summarize") || summarizer == null) return digest;
        try {
            String provider = m.summaryProvider.isBlank() ? ctx.provider : m.summaryProvider;
            String instructions = prompts.template(m.summaryPromptFile, Prompts.DEFAULT_COMPRESS)
                    .replace("{max_chars}", Integer.toString(m.summaryMaxChars));
            String material = "本次运行的任务：\n" + ctx.trigger.label() + "\n\n进展记录：\n"
                    + digest(steps, steps.size(), 0, ctx.cfg.limits.toolResultMaxChars);
            String sum = summarizer.summarize(provider, instructions, material, ctx);
            if (sum == null || sum.isBlank()) return digest;
            String recent = digest(steps.subList(Math.max(0, steps.size() - m.keepRecentSteps), steps.size()),
                    m.keepRecentSteps, m.oldResultChars, ctx.cfg.limits.toolResultMaxChars);
            return "摘要：\n" + Text.clip(sum.trim(), m.summaryMaxChars) + (recent.isEmpty() ? "" : "\n\n最近几轮原文：\n" + recent);
        } catch (LlmException | RuntimeException e) {
            log.warn("agent", "compress.summary_failed", "模型摘要失败，改用机械摘要：" + e.getMessage(), ctx.cid, null);
            return digest;
        }
    }

    /** 机械摘要：最近 keepRecent 轮的工具结果保留（最多 fullChars 字），更早的只留前 oldChars 字。 */
    static String digest(List<Step> steps, int keepRecent, int oldChars, int fullChars) {
        StringBuilder sb = new StringBuilder();
        int n = steps.size();
        for (int i = 0; i < n; i++) {
            Step s = steps.get(i);
            boolean recent = i >= n - keepRecent;
            sb.append("第 ").append(s.step()).append(" 轮");
            if (s.text() != null && !s.text().isBlank()) sb.append("，你说：").append(Text.brief(s.text(), recent ? 1000 : 200));
            sb.append('\n');
            for (Llm.ToolResult r : s.results()) {
                sb.append("  - 调用 ").append(r.call().name()).append(' ').append(Text.brief(r.call().args().toString(), 300))
                        .append(r.isError() ? " → 出错：" : " → ");
                String c = r.content();
                if (recent) sb.append(Text.clip(c, fullChars));
                else if (oldChars > 0) sb.append(Text.brief(c, oldChars));
                else sb.append("（结果已省略，").append(c.length()).append(" 字）");
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }
}
