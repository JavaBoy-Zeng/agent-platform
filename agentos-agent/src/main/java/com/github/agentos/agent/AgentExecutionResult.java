package com.github.agentos.agent;

import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingAction;

import java.util.Objects;

/** Agent 抽象返回给调用方的稳定执行结果。 */
public record AgentExecutionResult(
        AgentState.Status status,
        String output,
        String error,
        PendingAction pendingAction) {

    /** 规范化可选文本并校验状态。 */
    public AgentExecutionResult {
        status = Objects.requireNonNull(status, "status must not be null");
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    /** 从 Runtime 状态与当前挂起动作创建结果。 */
    public static AgentExecutionResult from(AgentState state, PendingAction action) {
        Objects.requireNonNull(state, "state must not be null");
        return new AgentExecutionResult(state.status(), state.output(), state.error(), action);
    }
}
