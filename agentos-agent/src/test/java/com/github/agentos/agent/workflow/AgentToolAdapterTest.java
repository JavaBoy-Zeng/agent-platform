package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.AgentTool;
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
        List<Map<String, Object>> attributes = new java.util.ArrayList<>();
        AgentToolAdapter adapter = new AgentToolAdapter(
                new BaseAgent("research-agent", "research helper", List.of()) {
                    @Override
                    public AgentState run(
                            AgentRequest request, InvocationContext context,
                            AgentState runningState, AgentEventSink eventSink) {
                        objectives.add(request.objective());
                        attributes.add(request.attributes());
                        assertThat(request.sessionId()).isEqualTo("session-9");
                        return runningState.complete("research result");
                    }
                });

        ToolResult result = adapter.execute(
                context(adapter, Map.of("approvalMode", "REQUEST_APPROVAL")),
                new ToolCall("research-agent", Map.of(
                AgentToolAdapter.OBJECTIVE_PARAMETER, "研究一下 JVM",
                AgentToolAdapter.SESSION_ID_PARAMETER, "session-9")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("research result");
        assertThat(objectives).containsExactly("研究一下 JVM");
        assertThat(attributes).singleElement()
                .satisfies(value -> assertThat(value)
                        .containsEntry("approvalMode", "REQUEST_APPROVAL"));
    }

    @Test
    void exposesConfiguredRiskLevel() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("report-agent", state -> state.complete("done")),
                AgentTool.RiskLevel.HIGH);

        assertThat(adapter.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
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
    void rejectsWaitingStateWithoutPendingAction() {
        AgentToolAdapter adapter = new AgentToolAdapter(
                scripted("agent", state -> state.waitForAction("approval")));

        ToolResult result = adapter.execute(context(adapter), new ToolCall("agent",
                Map.of(AgentToolAdapter.OBJECTIVE_PARAMETER, "do work")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.PERMISSION_DENIED);
    }

    @Test
    void subAgentRunsInIsolatedInvocationWithContextForwarding() {
        // 场景：父 invocation 已有 modelCalls=2/toolCalls=1，子 Agent 执行后
        // 父的计数器不变（真隔离）；子 Agent 发出的 AgentRunEvent 经转发 sink
        // 发到父的事件流，带 subagentId/subInvocationId 标签。
        com.github.agentos.kernel.AgentInvocation parentInvocation =
                new com.github.agentos.kernel.AgentInvocation(
                        "parent-inv-1", "session-iso", "plan-execute-agent", "", java.time.Instant.now());
        parentInvocation.start();
        parentInvocation.incrementModelCalls();
        parentInvocation.incrementModelCalls();
        parentInvocation.incrementToolCalls();
        int parentModelBefore = parentInvocation.modelCalls();
        int parentToolBefore = parentInvocation.toolCalls();

        java.util.List<com.github.agentos.kernel.AgentEvent> forwardedEvents =
                new java.util.ArrayList<>();
        com.github.agentos.kernel.AgentEventPublisher publisher =
                event -> forwardedEvents.add(event);

        InvocationContext parentContext = InvocationContext.of("plan-execute-agent")
                .withRuntime(parentInvocation, publisher);

        BaseAgent childAgent = new BaseAgent("child-agent", "isolated child", List.of()) {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context,
                    AgentState runningState, AgentEventSink eventSink) {
                // 子 Agent 发出 TOOL_STARTED + TOOL_FINISHED 事件。
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.TOOL_STARTED,
                        request.sessionId(), "子工具开始", Map.of()));
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.TOOL_FINISHED,
                        request.sessionId(), "子工具完成", Map.of()));
                return runningState.complete("子 Agent 结论");
            }
        };
        AgentToolAdapter adapter = new AgentToolAdapter(childAgent);

        ToolResult result = adapter.execute(
                new ToolContext(
                        new AgentRequest("session-iso", "test", Map.of()),
                        parentContext, "", "", AgentExecutionLimits.defaults(),
                        Map.of(), adapter),
                new ToolCall("child-agent", Map.of(
                        AgentToolAdapter.OBJECTIVE_PARAMETER, "隔离测试")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("子 Agent 结论");
        // 父的计数器未被污染（真隔离）。
        assertThat(parentInvocation.modelCalls()).isEqualTo(parentModelBefore);
        assertThat(parentInvocation.toolCalls()).isEqualTo(parentToolBefore);
        // 子 Agent 事件经转发 sink 发到父的事件流。
        assertThat(forwardedEvents).isNotEmpty();
        // 转发的事件 data 中携带 subagentId/subInvocationId 标签。
        com.github.agentos.kernel.AgentEvent firstForwarded = forwardedEvents.getFirst();
        assertThat(firstForwarded.data()).containsKey("subagentId");
        assertThat(firstForwarded.data().containsKey("subagentId")).isTrue();
        assertThat(firstForwarded.data().get("subagentId")).isEqualTo("child-agent");
        assertThat(firstForwarded.data()).containsKey("subInvocationId");
        assertThat(firstForwarded.agentId()).isEqualTo("child-agent");
    }

    private static ToolContext context(AgentToolAdapter adapter) {
        return context(adapter, Map.of());
    }

    private static ToolContext context(
            AgentToolAdapter adapter, Map<String, Object> attributes) {
        return new ToolContext(
                new AgentRequest("session-9", "test", attributes),
                InvocationContext.of("plan-execute-agent"),
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
