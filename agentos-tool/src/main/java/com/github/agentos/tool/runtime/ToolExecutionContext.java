package com.github.agentos.tool.runtime;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;

import java.util.Map;
import java.util.Objects;

/** 一次工具调用所需的 Invocation、计划步骤和运行预算上下文。 */
public record ToolExecutionContext(
        AgentRequest request,
        InvocationContext agentContext,
        String planId,
        String stepId,
        AgentExecutionLimits executionLimits,
        Map<String, Object> state,
        AgentTool tool) {

    /** 复制状态并校验工具调用上下文。 */
    public ToolExecutionContext {
        request = Objects.requireNonNull(request, "request must not be null");
        agentContext = Objects.requireNonNull(agentContext, "agentContext must not be null");
        planId = planId == null ? "" : planId.trim();
        stepId = stepId == null ? "" : stepId.trim();
        executionLimits = Objects.requireNonNull(
                executionLimits, "executionLimits must not be null");
        state = state == null ? Map.of() : Map.copyOf(state);
        tool = Objects.requireNonNull(tool, "tool must not be null");
    }

    public String sessionId() { return request.sessionId(); }
    public String invocationId() { return agentContext.invocationId(); }
    public String agentId() { return agentContext.agentId(); }
}
