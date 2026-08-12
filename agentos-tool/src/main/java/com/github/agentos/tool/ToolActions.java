package com.github.agentos.tool;

/** 工具结果向 Runtime 返回的控制动作。 */
public record ToolActions(boolean requestReplan, boolean endInvocation) {

    /** 返回不改变 Runtime 控制流的默认动作。 */
    public static ToolActions none() {
        return new ToolActions(false, false);
    }

    /** 请求 Runtime 基于当前结果重新规划。 */
    public static ToolActions replan() {
        return new ToolActions(true, false);
    }

    /** 请求 Runtime 在消费结果后结束本次 Invocation。 */
    public static ToolActions end() {
        return new ToolActions(false, true);
    }
}
