package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent 包装为工具的适配测试。 */
class AgentToolAdapterTest {

    @Test
    void exposesAgentIdentityAsToolDefinition() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("research-agent", state -> state.complete("done")));

        assertThat(adapter.name()).isEqualTo("research-agent");
        assertThat(adapter.description()).isEqualTo("scripted research-agent");
        assertThat(adapter.parameters())
                .extracting(ToolParameter::name)
                .containsExactly(
                        AgentToolAdapter.OBJECTIVE_PARAMETER,
                        AgentToolAdapter.SESSION_ID_PARAMETER);
    }

    @Test
    void executesWrappedAgentWithObjectiveArgument() {
        List<String> objectives = new java.util.ArrayList<>();
        AgentToolAdapter adapter = new AgentToolAdapter(
                new BaseAgent("research-agent", "research helper", List.of()) {
                    @Override
                    public AgentState run(
                            AgentRequest request, InvocationContext context,
                            AgentState runningState, AgentEventSink eventSink) {
                        objectives.add(request.objective());
                        assertThat(request.sessionId()).isEqualTo("session-9");
                        return runningState.complete("research result");
                    }
                });

        ToolResult result = adapter.execute(context(adapter), new ToolCall("research-agent", Map.of(
                AgentToolAdapter.OBJECTIVE_PARAMETER, "研究一下 JVM",
                AgentToolAdapter.SESSION_ID_PARAMETER, "session-9")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("research result");
        assertThat(objectives).containsExactly("研究一下 JVM");
    }

    @Test
    void rejectsMissingObjective() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("agent", state -> state.complete("never")));

        ToolResult result = adapter.execute(context(adapter), new ToolCall("agent", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void mapsAgentFailureToToolFailure() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("agent", state -> state.fail("agent exploded")));

        ToolResult result = adapter.execute(context(adapter), new ToolCall("agent",
                Map.of(AgentToolAdapter.OBJECTIVE_PARAMETER, "do work")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("agent exploded");
        assertThat(result.failureType()).isEqualTo(ToolFailureType.TOOL_INTERNAL_ERROR);
    }

    @Test
    void mapsWaitingStateToFailureBecauseApprovalCannotResume() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("agent", state -> state.waitForAction("approval")));

        ToolResult result = adapter.execute(context(adapter), new ToolCall("agent",
                Map.of(AgentToolAdapter.OBJECTIVE_PARAMETER, "do work")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.PERMISSION_DENIED);
    }

    private static ToolContext context(AgentToolAdapter adapter) {
        return new ToolContext(
                AgentRequest.of("session-9", "test"),
                InvocationContext.of("main-agent"),
                "", "", AgentExecutionLimits.defaults(), Map.of(), adapter);
    }

    private static BaseAgent scripted(String id, java.util.function.Function<AgentState, AgentState> behavior) {
        return new BaseAgent(id, "scripted " + id, List.of()) {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context,
                    AgentState runningState, AgentEventSink eventSink) {
                return behavior.apply(runningState);
            }
        };
    }
}
