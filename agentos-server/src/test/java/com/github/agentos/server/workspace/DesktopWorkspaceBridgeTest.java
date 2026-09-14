package com.github.agentos.server.workspace;

import com.github.agentos.kernel.*;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.hitl.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopWorkspaceBridgeTest {
    private final InMemorySessionService sessions = new InMemorySessionService();
    private final AgentRunTaskRegistry tasks = new AgentRunTaskRegistry();
    private final DesktopWorkspaceBridge bridge = new DesktopWorkspaceBridge(sessions, tasks);
    private final DesktopWorkspaceBridge.Binding binding = new DesktopWorkspaceBridge.Binding(
            "workspace-1", "/selected/project", "main", "Alice Mac", "macos");
    private final InvocationContext invocation = new InvocationContext("team", "alice", "workspace-agent", "");
    private AgentTool tool() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("run_command");
        when(tool.riskLevel()).thenReturn(AgentTool.RiskLevel.HIGH);
        when(tool.description()).thenReturn("run local command");
        return tool;
    }
    private ToolContext context(AgentTool tool, AgentRequest request) {
        return new ToolContext(request, invocation, "", "", AgentExecutionLimits.defaults(), Map.of(), tool);
    }
    @Test void routesCommandsToDesktopAndNeverCallsServerTool() throws Exception {
        var registration = bridge.register("parent", "alice", binding);
        AgentTool tool = tool();
        var request = bridge.prepare(AgentRequest.of("parent", "pwd"), "alice");
        var dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)), List.of(bridge));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> dispatcher.dispatch(new ToolCall("run_command", Map.of("command", "pwd")),
                    t -> context(t, request)));
            var operation = awaitOperation("parent", registration.token());
            assertThat(operation.arguments()).containsEntry("command", "pwd");
            bridge.complete("parent", "alice", registration.token(), operation.id(),
                    new DesktopWorkspaceBridge.Completion(true, Map.of("cwd", binding.root(), "stdout", binding.root()), null, false));
            assertThat(future.get(3, TimeUnit.SECONDS).output()).contains(binding.root(), "workspace-1");
            verify(tool, never()).execute(any(), any());
        }
    }
    @Test void delegatedWorkspaceAgentInheritsBindingWithDifferentChildSession() throws Exception {
        var usage = new com.github.agentos.server.usage.UsageRecorder(
                new com.github.agentos.server.usage.UsageStore.InMemoryUsageStore());
        var registration = bridge.register("parent", "alice", binding);
        var request = bridge.prepare(AgentRequest.of("parent", "test"), "alice");
        AgentTool local = tool();
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        var chat = new com.github.agentos.planner.ChatClient() {
            public String chat(String session, com.github.agentos.planner.flow.LlmRequest input) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, com.github.agentos.planner.flow.LlmRequest input,
                    java.util.function.Consumer<String> delta) {
                assertThat(session).isNotEqualTo("parent"); // 执行会话保持隔离，用量由内部作用域归根会话。
                usage.onModelUsage(session, new com.github.agentos.kernel.ModelUsage("test", 10, 5));
                assertThat(input.systemInstruction()).contains(binding.root(), "workspace-1", "本机");
                return rounds.incrementAndGet() == 1
                        ? ToolCallResponse.call(new ToolCall("run_command", Map.of("command", "pwd")), "call-1", null)
                        : ToolCallResponse.answer(binding.root(), null);
            }
        };
        var workspaceAgent = new com.github.agentos.agent.specialist.ToolProviderAgent("workspace-agent", "workspace", "执行工作区任务",
                Set.of(com.github.agentos.agent.routing.AgentCapability.COMMAND_EXECUTION),
                Set.of(com.github.agentos.agent.routing.RouteScope.LOCAL_WORKSPACE), ctx -> List.of(local), chat);
        workspaceAgent.configureExecution(new ToolDispatcher(new ToolRegistry(List.of(local)), List.of(bridge)),
                com.github.agentos.agent.loop.ContinuationStore.NOOP, AgentExecutionLimits.defaults());
        var adapter = new AgentToolAdapter(workspaceAgent);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var parentInvocation = new com.github.agentos.kernel.AgentInvocation(
                    "parent-run", "parent", "react-agent", "", java.time.Instant.now());
            var parentContext = new ToolContext(request, invocation.withInvocation(parentInvocation),
                    "", "", AgentExecutionLimits.defaults(), Map.of(), adapter);
            var future = pool.submit(() -> adapter.execute(parentContext,
                    new ToolCall("workspace-agent", Map.of("objective", "pwd"))));
            var operation = awaitOperation("parent", registration.token());
            bridge.complete("parent", "alice", registration.token(), operation.id(),
                    new DesktopWorkspaceBridge.Completion(true, Map.of("cwd", binding.root()), null, false));
            assertThat(future.get(3, TimeUnit.SECONDS).output()).contains(binding.root());
            assertThat(usage.summary("parent").totalTokens()).isEqualTo(30);
            assertThat(usage.summary("parent").modelCalls()).isEqualTo(2);
        }
    }
    @Test void persistentBindingFailsClosedAfterRestartOrDisconnect() {
        var registration = bridge.register("parent", "alice", binding);
        var request = bridge.prepare(AgentRequest.of("parent", "read"), "alice");
        bridge.disconnect("parent", "alice", registration.token());
        assertThatThrownBy(() -> bridge.prepare(AgentRequest.of("parent", "retry"), "alice"))
                .hasMessageContaining("离线");
        var restarted = new DesktopWorkspaceBridge(sessions, tasks);
        var result = restarted.beforeExecute(new ToolCall("run_command", Map.of("command", "pwd")), context(tool(), request));
        assertThat(result.proceed()).isFalse();
        assertThat(result.result().actions().pendingAction().type()).isEqualTo(PendingActionType.HUMAN_INPUT);
    }
    @Test void rejectsOtherUsersTokensAndRebindingDirectory() {
        var registration = bridge.register("parent", "alice", binding);
        assertThatThrownBy(() -> bridge.poll("parent", "bob", registration.token())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.poll("parent", "alice", "wrong")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.register("parent", "alice", new DesktopWorkspaceBridge.Binding(
                "another", "/another", "main", "Mac", "macos"))).hasMessageContaining("已绑定其他工作区");
    }
    @Test void approvalHappensBeforeAnythingIsSentToDesktop() throws Exception {
        var registration = bridge.register("parent", "alice", binding);
        var request = bridge.prepare(new AgentRequest("parent", "test", Map.of("approvalMode", "REQUEST_APPROVAL")), "alice");
        AgentTool tool = tool();
        var approval = new ApprovalToolInterceptor(mock(RiskPolicy.class), mock(ApprovalService.class));
        var dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)), List.of(approval, bridge));
        var result = dispatcher.dispatch(new ToolCall("run_command", Map.of("command", "mvn test")), t -> context(t, request));
        assertThat(result.actions().pendingAction().type()).isEqualTo(PendingActionType.HUMAN_APPROVAL);
        assertThat(bridge.poll("parent", "alice", registration.token()).operation()).isNull();
        verify(tool, never()).execute(any(), any());
    }
    @Test void resumedTaskRejectsDifferentBranch() {
        bridge.register("parent", "alice", binding);
        var request = bridge.prepare(AgentRequest.of("parent", "test"), "alice");
        bridge.register("parent", "alice", new DesktopWorkspaceBridge.Binding("workspace-1", binding.root(), "other", "Mac", "macos"));
        var result = bridge.beforeExecute(new ToolCall("run_command", Map.of("command", "pwd")), context(tool(), request));
        assertThat(result.result().actions().pendingAction().description()).contains("分支已改变");
    }
    private DesktopWorkspaceBridge.Operation awaitOperation(String sessionId, String token) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var operation = bridge.poll(sessionId, "alice", token).operation();
            if (operation != null) return operation;
            Thread.sleep(10);
        }
        throw new AssertionError("desktop operation was not enqueued");
    }
}
