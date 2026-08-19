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
        return new ToolContext(
                AgentRequest.of("test-session", "test"),
                InvocationContext.of("test-agent"),
                "", "", AgentExecutionLimits.defaults(), Map.of(), tool);
    }
}
