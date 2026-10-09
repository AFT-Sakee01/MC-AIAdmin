package io.github.aftsakee.aiadmin.agent;

import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.llm.Llm.LlmException;
import io.github.aftsakee.aiadmin.llm.Llm.Reply;
import io.github.aftsakee.aiadmin.llm.Llm.ToolCall;
import io.github.aftsakee.aiadmin.llm.Llm.ToolResult;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次运行的工具循环：模型回复 → 依次执行它要求的工具 → 把结果交回 → 直到它不再调用工具。
 * <ul>
 *   <li>第一次请求就失败时抛出 {@link LlmException}（调用方可以换备用接入点）；之后失败则带着错误返回，
 *       不能换接入点重来——前面可能已经执行过指令，重来会重复执行。</li>
 *   <li>上下文压缩：{@link Compressor#due} 判定后，把进展压成摘要，用新会话以"原任务 + 摘要 + 已执行动作清单"重新开始
 *       （本轮工具结果已包含在摘要里，不再 submit 给旧会话）。</li>
 * </ul>
 */
public final class AgentRunner {
    public record Result(String text, int steps, Llm.Usage usage, String error, int compressions) {}

    @FunctionalInterface
    public interface SessionFactory {
        Llm.Session open() throws LlmException;
    }

    private AgentRunner() {}

    public static Result run(SessionFactory factory, String system, String user, Tools tools, RunContext ctx, JsonlLog log,
                             long deadlineMillis, Compressor comp) throws LlmException {
        int maxSteps = ctx.rules.quota.perRun.maxSteps;
        int maxChars = ctx.cfg.limits.toolResultMaxChars;
        Llm.Usage usage = Llm.Usage.ZERO;
        Llm.Session session = factory.open();
        Reply r = session.start(system, user);
        long contextChars = system.length() + user.length();
        List<Compressor.Step> transcript = new ArrayList<>();
        int compressions = 0;
        int lastCompress = 0;
        int segmentStart = 1;   // 当前会话从第几轮开始（压缩后重开会话时更新）
        String carried = "";    // 之前几次压缩累积的进展，下次压缩时一起带上，避免第二次压缩丢掉更早的进展
        int step = 1;
        while (true) {
            usage = usage.plus(r.usage());
            List<String> names = new ArrayList<>();
            for (ToolCall c : r.calls()) names.add(c.name());
            log.info("agent", "llm.reply", "第 " + step + " 轮回复：" + Text.brief(r.text(), 200), ctx.cid,
                    JsonlLog.data("step", step, "stop_reason", r.stopReason(), "tool_calls", names,
                            "input_tokens", r.usage().input(), "output_tokens", r.usage().output(), "context_chars", contextChars));
            if (r.calls().isEmpty()) return new Result(r.text(), step, usage, null, compressions);
            if (truncated(r.stopReason())) {
                // 回复被输出上限截断时，最后一个工具调用的参数可能不完整（例如半截指令），一个都不执行
                return new Result(r.text(), step, usage, "回复被输出上限截断（" + r.stopReason() + "），未执行其中的工具调用；可调大 max_tokens", compressions);
            }
            if (ctx.cancelled()) return new Result(r.text(), step, usage, "已取消", compressions);
            if (step >= maxSteps) return new Result(r.text(), step, usage, "达到每次运行的轮数上限 " + maxSteps, compressions);
            if (System.currentTimeMillis() > deadlineMillis) return new Result(r.text(), step, usage, "运行超时", compressions);

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall c : r.calls()) {
                ToolResult tr = execute(c, tools, ctx, log, maxChars);
                results.add(tr);
                contextChars += c.args().toString().length() + tr.content().length();
            }
            contextChars += r.text().length();
            transcript.add(new Compressor.Step(step, r.text(), results));
            try {
                if (comp.due(step, r.usage().input(), contextChars, compressions, lastCompress)) {
                    String digest = comp.compress(transcript, ctx, log);
                    String progress = (carried.isEmpty() ? "" : carried + "\n\n") + "第 " + segmentStart + "–" + step + " 轮：\n" + digest;
                    carried = Text.clip(progress, Math.max(4000, ctx.cfg.compression.method.summaryMaxChars * 3));
                    segmentStart = step + 1;
                    List<String> acts = ctx.actions();
                    String user2 = user + "\n\n【上下文已压缩】以下是你本次运行到目前为止的进展：\n" + carried
                            + (acts.isEmpty() ? "" : "\n\n已经执行过的动作（不要重复）：\n- " + String.join("\n- ", acts))
                            + "\n\n请在此基础上继续完成任务。";
                    log.info("agent", "context.compressed", "上下文压缩：第 " + step + " 轮，" + contextChars + " 字 → " + (system.length() + user2.length()) + " 字",
                            ctx.cid, JsonlLog.data("step", step, "before_chars", contextChars, "after_chars", system.length() + user2.length(),
                                    "last_input_tokens", r.usage().input(), "method", ctx.cfg.compression.method.type));
                    compressions++;
                    lastCompress = step;
                    session = factory.open();
                    r = session.start(system, user2);
                    contextChars = system.length() + user2.length();
                    transcript.clear();
                } else {
                    r = session.submit(results);
                }
            } catch (LlmException e) {
                return new Result("", step, usage, e.getMessage(), compressions);
            }
            step++;
        }
    }

    static boolean truncated(String stopReason) {
        return "max_tokens".equals(stopReason) || "length".equals(stopReason) || "incomplete".equals(stopReason);
    }

    static ToolResult execute(ToolCall c, Tools tools, RunContext ctx, JsonlLog log, int maxChars) {
        if (ctx.cancelled()) return new ToolResult(c, "运行已被管理员取消", true);
        Tools.Tool t = ctx.rules.permissions.hiddenTools().contains(c.name()) ? null : tools.get(c.name());
        if (t == null) return new ToolResult(c, "没有这个工具：" + c.name(), true);
        if (c.argsError() != null) return new ToolResult(c, c.argsError(), true);
        long t0 = System.nanoTime();
        try {
            String out = t.handler().call(c.args(), ctx);
            log.info("agent", "tool.call", c.name() + " " + Text.brief(c.args().toString(), 300), ctx.cid,
                    JsonlLog.data("tool", c.name(), "ms", (System.nanoTime() - t0) / 1_000_000, "result_chars", out.length()));
            return new ToolResult(c, Text.clip(out, maxChars), false);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + "：" + e.getMessage();
            log.warn("agent", "tool.error", c.name() + " 出错：" + msg, ctx.cid, JsonlLog.data("tool", c.name(), "args", Text.brief(c.args().toString(), 300)));
            return new ToolResult(c, msg, true);
        }
    }
}
