package com.github.agentos.tool.api;

import com.github.agentos.kernel.PendingAction;

import java.util.Map;
import java.util.Objects;

/** 工具结果向 Runtime 返回的控制动作。 */
public record ToolActions(
        boolean requestReplan,
        boolean endInvocation,
        Map<String, Object> stateDelta,
        PendingAction pendingAction) {

    /** 创建并规范化状态增量。 */
    public ToolActions {
        stateDelta = stateDelta == null ? Map.of() : Map.copyOf(stateDelta);
    }

    /** 返回不改变 Runtime 控制流的默认动作。 */
    public static ToolActions none() {
        return new ToolActions(false, false, Map.of(), null);
    }

    /** 请求 Runtime 基于当前结果重新规划。 */
    public static ToolActions replan() {
        return new ToolActions(true, false, Map.of(), null);
    }

    /** 请求 Runtime 在消费结果后结束本次 Invocation。 */
    public static ToolActions end() {
        return new ToolActions(false, true, Map.of(), null);
    }

    /** 请求 Runtime 把状态增量合并进会话状态。 */
    public static ToolActions stateDelta(Map<String, Object> delta) {
        return new ToolActions(false, false, delta, null);
    }

    /** 请求 Runtime 挂起并等待指定动作解决。 */
    public static ToolActions pending(PendingAction action) {
        return new ToolActions(false, false, Map.of(),
                Objects.requireNonNull(action, "action must not be null"));
    }
}
