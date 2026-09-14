package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime 内部事件到公开 AgentStreamEvent 生命周期的边界测试。 */
class RuntimeStreamEventMapperTest {

    @Test
    void neverMapsInternalExecutionTraces() {
        assertThat(RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.OBSERVATION, "s1", "raw provider response",
                Map.of("traceKind", "model_response", "text", "secret"))))
                .isEmpty();
    }

    @Test
    void mapsPlanningToSafeUserStatusInsteadOfInternalMessage() {
        var mapped = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.PLAN_CREATED, "s1", "hidden reasoning", Map.of()))
                .getFirst();

        assertThat(mapped.type()).isEqualTo(AgentStreamEvent.Type.STATUS);
        assertThat(mapped.data()).containsEntry("text", "正在分析并规划任务");
        assertThat(mapped.data().toString()).doesNotContain("hidden reasoning");
    }

    @Test
    void mapsToolLifecycleByStableToolCallId() {
        var started = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_STARTED, "s1", "start",
                Map.of("toolCallId", "tool_1", "toolName", "file_read",
                        "arguments", Map.of("path", "README.md")))).getFirst();
        var completed = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_FINISHED, "s1", "done",
                Map.of("toolCallId", "tool_1", "toolName", "file_read",
                        "arguments", Map.of("path", "README.md"),
                        "success", true, "summary", "120 lines"))).getFirst();

        assertThat(started.type()).isEqualTo(AgentStreamEvent.Type.TOOL_STARTED);
        assertThat(started.itemId()).isEqualTo("tool_1");
        assertThat(completed.type()).isEqualTo(AgentStreamEvent.Type.TOOL_COMPLETED);
        assertThat(completed.itemId()).isEqualTo("tool_1");
        assertThat(completed.data()).containsEntry("summary", "120 lines");
    }

    @Test
    void keepsToolFailureSeparateFromRunFailure() {
        var failed = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_FINISHED, "s1", "Command timed out",
                Map.of("toolCallId", "tool_2", "toolName", "run_command",
                        "success", false, "failureType", "TIMEOUT"))).getFirst();

        assertThat(failed.type()).isEqualTo(AgentStreamEvent.Type.TOOL_FAILED);
        Map<?, ?> error = (Map<?, ?>) failed.data().get("error");
        assertThat(error.get("code")).isEqualTo("TOOL_TIMEOUT");
        assertThat(error.get("retryable")).isEqualTo(true);
    }

    @Test
    void onlyRealDomainApprovalCreatesToolAwaitingApproval() {
        var mapped = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.DECISION, "s1", "approve",
                Map.of("domainEventType", "HUMAN_ACTION_REQUIRED",
                        "toolCallId", "tool_3", "pendingActionId", "pending_3")));
        var loopDecision = RuntimeStreamEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.DECISION, "s1", "duplicate",
                Map.of("pendingActionId", "pending_3")));

        assertThat(mapped.getFirst().type())
                .isEqualTo(AgentStreamEvent.Type.TOOL_AWAITING_APPROVAL);
        assertThat(loopDecision.getFirst().type()).isEqualTo(AgentStreamEvent.Type.STATUS);
    }
}
