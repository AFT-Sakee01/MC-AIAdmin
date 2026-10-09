package io.github.aftsakee.aiadmin.llm;

import com.google.gson.JsonObject;

import java.util.List;

/** 与具体协议无关的对话数据类型。 */
public final class Llm {
    private Llm() {}

    /** 给模型看的工具定义。parameters 是 JSON Schema（type=object）。 */
    public record ToolSpec(String name, String description, JsonObject parameters) {}

    /** 模型要求调用的工具。args 解析失败时 args 为空对象、argsError 写原因。 */
    public record ToolCall(String id, String name, JsonObject args, String argsError) {}

    public record ToolResult(ToolCall call, String content, boolean isError) {}

    public record Usage(long input, long output) {
        public static final Usage ZERO = new Usage(0, 0);

        public long total() {
            return input + output;
        }

        public Usage plus(Usage o) {
            return o == null ? this : new Usage(input + o.input, output + o.output);
        }
    }

    /** 模型的一次回复：文字 + 要调用的工具（为空 = 本次运行结束）。 */
    public record Reply(String text, List<ToolCall> calls, Usage usage, String stopReason) {}

    /** 一次运行的会话（保存各协议自己的历史）。不是线程安全的，一次运行只在一个线程里用。 */
    public interface Session {
        Reply start(String system, String user) throws LlmException;

        Reply submit(List<ToolResult> results) throws LlmException;
    }

    public static final class LlmException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        public LlmException(String msg, int status) {
            super(msg);
            this.status = status;
        }

        public LlmException(String msg, Throwable cause) {
            super(msg, cause);
            this.status = -1;
        }

        /** HTTP 状态码；-1 = 网络错误或解析错误。 */
        public int status() {
            return status;
        }
    }
}
