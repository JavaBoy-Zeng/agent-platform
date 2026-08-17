package com.github.agentos.hitl;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolBeforeResult;
import com.github.agentos.tool.runtime.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalToolInterceptorTest {

    @Test
    void shortCircuitsRiskyToolWithHumanApprovalAction() {
        AgentTool tool = tool(AgentTool.RiskLevel.HIGH);
        ToolCall call = new ToolCall("test", Map.of("path", "docs/readme.md"));
        ApprovalToolInterceptor interceptor = interceptor(AgentTool.RiskLevel.MEDIUM);

        ToolBeforeResult result = interceptor.beforeExecute(call, context(tool));

        assertThat(result.proceed()).isFalse();
        assertThat(result.result().actions().pendingAction()).satisfies(action -> {
            assertThat(action.type()).isEqualTo(PendingActionType.HUMAN_APPROVAL);
            assertThat(action.payload())
                    .containsEntry("toolName", "test")
                    .containsEntry("arguments", call.arguments())
                    .containsEntry("riskLevel", AgentTool.RiskLevel.HIGH.name());
        });
    }

    @Test
    void allowsToolBelowApprovalThreshold() {
        AgentTool tool = tool(AgentTool.RiskLevel.LOW);

        ToolBeforeResult result = interceptor(AgentTool.RiskLevel.MEDIUM).beforeExecute(
                new ToolCall("test", Map.of()), context(tool));

        assertThat(result.proceed()).isTrue();
        assertThat(result.result()).isNull();
    }

    private static ApprovalToolInterceptor interceptor(AgentTool.RiskLevel threshold) {
        return new ApprovalToolInterceptor(
                new RiskPolicy(threshold), new ApprovalService(request -> false));
    }

    private static ToolExecutionContext context(AgentTool tool) {
        return new ToolExecutionContext(
                AgentRequest.of("session-1", "test"),
                AgentContext.of("main-agent"),
                "plan-1",
                "step-1",
                new AgentExecutionLimits(1, 1, 1, 1),
                Map.of(),
                tool);
    }

    private static AgentTool tool(AgentTool.RiskLevel riskLevel) {
        return new AgentTool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test tool"; }
            @Override public RiskLevel riskLevel() { return riskLevel; }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.success("ok"); }
        };
    }
}
