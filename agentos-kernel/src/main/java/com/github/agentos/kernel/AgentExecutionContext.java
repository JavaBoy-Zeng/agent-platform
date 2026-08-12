package com.github.agentos.kernel;

import java.util.Objects;

/** ExecutionPolicy 和执行策略共享的运行上下文。 */
public record AgentExecutionContext(
        AgentContext agentContext,
        AgentExecutionLimits executionLimits) {

    /** 校验 Agent 身份上下文和本次执行预算。 */
    public AgentExecutionContext {
        agentContext = Objects.requireNonNull(agentContext, "agentContext must not be null");
        executionLimits = Objects.requireNonNull(
                executionLimits, "executionLimits must not be null");
    }

    /** 使用默认预算创建执行上下文。 */
    public static AgentExecutionContext of(AgentContext agentContext) {
        return new AgentExecutionContext(agentContext, AgentExecutionLimits.defaults());
    }
}
