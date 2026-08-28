package com.github.agentos.hitl;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolBeforeResult;
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

    @Test
    void fullAccessBypassesRiskApproval() {
        AgentTool tool = tool("file_write", AgentTool.RiskLevel.HIGH);

        ToolBeforeResult result = interceptor(AgentTool.RiskLevel.LOW).beforeExecute(
                new ToolCall("file_write", Map.of("path", "notes.txt")),
                context(tool, Map.of("approvalMode", "FULL_ACCESS")));

        assertThat(result.proceed()).isTrue();
    }

    @Test
    void requestApprovalGuardsInternetToolsEvenWhenLowRisk() {
        AgentTool tool = tool("browser_search", AgentTool.RiskLevel.LOW);

        ToolBeforeResult result = interceptor(AgentTool.RiskLevel.HIGH).beforeExecute(
                new ToolCall("browser_search", Map.of("query", "AgentOS")),
                context(tool, Map.of("approvalMode", "REQUEST_APPROVAL")));

        assertThat(result.proceed()).isFalse();
    }

    @Test
    void requestApprovalGuardsFileWrites() {
        AgentTool tool = tool("file_write", AgentTool.RiskLevel.HIGH);

        ToolBeforeResult result = interceptor(AgentTool.RiskLevel.MEDIUM).beforeExecute(
                new ToolCall("file_write", Map.of(
                        "path", "notes.txt", "mode", "CREATE_NEW")),
                context(tool, Map.of("approvalMode", "REQUEST_APPROVAL")));

        assertThat(result.proceed()).isFalse();
        assertThat(result.result().actions().pendingAction().payload())
                .containsEntry("toolName", "file_write");
    }

    @Test
    void requestApprovalAlsoGuardsRiskyCompositeAgentTools() {
        AgentTool tool = tool("report-agent", AgentTool.RiskLevel.HIGH);

        ToolBeforeResult result = interceptor(AgentTool.RiskLevel.MEDIUM).beforeExecute(
                new ToolCall("report-agent", Map.of("objective", "写报告")),
                context(tool, Map.of("approvalMode", "REQUEST_APPROVAL")));

        assertThat(result.proceed()).isFalse();
    }

    private static ApprovalToolInterceptor interceptor(AgentTool.RiskLevel threshold) {
        return new ApprovalToolInterceptor(
                new RiskPolicy(threshold), new ApprovalService(request -> false));
    }

    private static ToolContext context(AgentTool tool) {
        return context(tool, Map.of());
    }

    private static ToolContext context(AgentTool tool, Map<String, Object> attributes) {
        return new ToolContext(
                new AgentRequest("session-1", "test", attributes),
                InvocationContext.of("main-agent"),
                "plan-1",
                "step-1",
                new AgentExecutionLimits(1, 1, 1, 1),
                Map.of(),
                tool);
    }

    private static AgentTool tool(AgentTool.RiskLevel riskLevel) {
        return tool("test", riskLevel);
    }

    private static AgentTool tool(String name, AgentTool.RiskLevel riskLevel) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool"; }
            @Override public RiskLevel riskLevel() { return riskLevel; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) { return ToolResult.success("ok"); }
        };
    }
}
