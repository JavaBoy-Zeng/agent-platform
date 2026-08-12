package com.github.agentos.tool;

import com.github.agentos.kernel.PendingAction;

/** 工具结果向 Runtime 返回的控制动作。 */
public record ToolActions(
        boolean requestReplan, boolean endInvocation, PendingAction pendingAction) {

    /** 返回不改变 Runtime 控制流的默认动作。 */
    public static ToolActions none() {
        return new ToolActions(false, false, null);
    }

    /** 请求 Runtime 基于当前结果重新规划。 */
    public static ToolActions replan() {
        return new ToolActions(true, false, null);
    }

    /** 请求 Runtime 在消费结果后结束本次 Invocation。 */
    public static ToolActions end() {
        return new ToolActions(false, true, null);
    }

    /** 请求 Runtime 挂起并等待指定动作解决。 */
    public static ToolActions pending(PendingAction action) {
        return new ToolActions(false, false,
                java.util.Objects.requireNonNull(action, "action must not be null"));
    }
}
