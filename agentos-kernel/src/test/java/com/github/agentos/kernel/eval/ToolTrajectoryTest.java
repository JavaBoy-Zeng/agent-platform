package com.github.agentos.kernel.eval;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTrajectoryTest {

    @Test
    void fromEvents_pairsStartWithTerminalEvents() {
        ToolTrajectory trajectory = ToolTrajectory.fromEvents(List.of(
                event(AgentEventType.TOOL_CALL_STARTED, "search", Map.of()),
                event(AgentEventType.TOOL_CALL_COMPLETED, "search", Map.of()),
                event(AgentEventType.TOOL_CALL_STARTED, "file_read", Map.of()),
                event(AgentEventType.TOOL_CALL_FAILED, "file_read", Map.of("failureType", "NOT_FOUND"))));

        assertThat(trajectory.toolNames()).containsExactly("search", "file_read");
        assertThat(trajectory.calls().get(0).success()).isTrue();
        assertThat(trajectory.calls().get(1).success()).isFalse();
        assertThat(trajectory.calls().get(1).failureType()).isEqualTo("NOT_FOUND");
        assertThat(trajectory.failedCount()).isEqualTo(1);
    }

    @Test
    void fromEvents_keepsPendingCallWithoutTerminalEvent() {
        ToolTrajectory trajectory = ToolTrajectory.fromEvents(List.of(
                event(AgentEventType.TOOL_CALL_STARTED, "search", Map.of())));

        assertThat(trajectory.calls()).hasSize(1);
        assertThat(trajectory.calls().get(0).pending()).isTrue();
        assertThat(trajectory.calls().get(0).success()).isNull();
    }

    @Test
    void fromEvents_closesInterleavedSameNameCallsInLifoOrder() {
        ToolTrajectory trajectory = ToolTrajectory.fromEvents(List.of(
                event(AgentEventType.TOOL_CALL_STARTED, "search", Map.of()),
                event(AgentEventType.TOOL_CALL_STARTED, "search", Map.of()),
                event(AgentEventType.TOOL_CALL_COMPLETED, "search", Map.of()),
                event(AgentEventType.TOOL_CALL_FAILED, "search", Map.of("failureType", "TIMEOUT"))));

        assertThat(trajectory.toolNames()).containsExactly("search", "search");
        // 后开启的调用先被后到达的完成事件关闭。
        assertThat(trajectory.calls().get(0).success()).isFalse();
        assertThat(trajectory.calls().get(0).failureType()).isEqualTo("TIMEOUT");
        assertThat(trajectory.calls().get(1).success()).isTrue();
    }

    @Test
    void fromEvents_ignoresEventsWithoutToolName() {
        ToolTrajectory trajectory = ToolTrajectory.fromEvents(List.of(
                event(AgentEventType.TOOL_CALL_STARTED, null, Map.of()),
                event(AgentEventType.AGENT_COMPLETED, null, Map.of())));

        assertThat(trajectory.calls()).isEmpty();
    }

    @Test
    void fromEvents_nullOrEmptyYieldsEmptyTrajectory() {
        assertThat(ToolTrajectory.fromEvents(null).calls()).isEmpty();
        assertThat(ToolTrajectory.fromEvents(List.of()).calls()).isEmpty();
        assertThat(ToolTrajectory.empty().toolNames()).isEmpty();
    }

    private static AgentEvent event(AgentEventType type, String toolName, Map<String, Object> data) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(data);
        if (toolName != null) {
            payload.put("toolName", toolName);
        }
        return new DefaultAgentEvent(
                "event-" + Math.random(), "session-1", "inv-1", "main-agent",
                Instant.now(), type, "", payload);
    }
}
