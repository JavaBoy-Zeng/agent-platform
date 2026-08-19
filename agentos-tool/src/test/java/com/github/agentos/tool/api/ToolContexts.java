package com.github.agentos.tool.api;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;

import java.util.Map;

/** 单元测试专用的 {@link ToolContext} 工厂。 */
public final class ToolContexts {

    private ToolContexts() {
    }

    /** 创建以指定工具为中心的最小可用执行上下文。 */
    public static ToolContext testContext(AgentTool tool) {
        return testContext(tool, InvocationContext.of("test-agent"));
    }

    /** 创建携带指定 Invocation 上下文的执行上下文，供注入产物存储等运行时能力。 */
    public static ToolContext testContext(AgentTool tool, InvocationContext invocation) {
        return new ToolContext(
                AgentRequest.of("test-session", "test"),
                invocation,
                "", "", AgentExecutionLimits.defaults(), Map.of(), tool);
    }
}
