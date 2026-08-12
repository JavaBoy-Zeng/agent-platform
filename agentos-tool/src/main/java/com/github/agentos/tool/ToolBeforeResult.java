package com.github.agentos.tool;

import java.util.Objects;

/** ToolInterceptor 前置阶段的继续或短路决策。 */
public record ToolBeforeResult(boolean proceed, ToolResult result) {

    /** 允许继续执行后续拦截器和工具。 */
    public static ToolBeforeResult allow() {
        return new ToolBeforeResult(true, null);
    }

    /** 使用给定结果短路工具执行。 */
    public static ToolBeforeResult shortCircuit(ToolResult result) {
        return new ToolBeforeResult(false,
                Objects.requireNonNull(result, "result must not be null"));
    }

    /** 校验继续决策不能携带结果，短路决策必须携带结果。 */
    public ToolBeforeResult {
        if (proceed && result != null) {
            throw new IllegalArgumentException("proceed result must not carry a tool result");
        }
        if (!proceed && result == null) {
            throw new IllegalArgumentException("short circuit result must carry a tool result");
        }
    }
}
