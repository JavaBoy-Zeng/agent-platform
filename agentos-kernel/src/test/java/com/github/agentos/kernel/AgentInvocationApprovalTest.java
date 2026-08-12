package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentInvocationApprovalTest {

    @Test
    void consumesMatchingApprovalExactlyOnce() {
        AgentInvocation invocation = new AgentInvocation(
                "invocation-1", "session-1", "main-agent", "", Instant.now());
        invocation.waitFor(action("approval-1", "file_write", Map.of("path", "report.docx")));
        invocation.resolve(PendingActionResolution.approved("approval-1"));

        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "report.docx"))).isTrue();
        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "report.docx"))).isFalse();
        assertThat(invocation.pendingAction()).isNull();
        assertThat(invocation.resolution()).isNull();
    }

    @Test
    void doesNotConsumeMismatchedOrRejectedResolution() {
        AgentInvocation invocation = new AgentInvocation(
                "invocation-1", "session-1", "main-agent", "", Instant.now());
        invocation.waitFor(action("approval-1", "file_write", Map.of("path", "report.docx")));
        invocation.resolve(PendingActionResolution.approved("approval-2"));

        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "report.docx"))).isFalse();

        invocation.resolve(PendingActionResolution.rejected("approval-1"));
        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "report.docx"))).isFalse();
    }

    @Test
    void doesNotApproveChangedToolArguments() {
        AgentInvocation invocation = new AgentInvocation(
                "invocation-1", "session-1", "main-agent", "", Instant.now());
        invocation.waitFor(action(
                "approval-1", "file_write", Map.of("path", "report.docx", "mode", "CREATE_NEW")));
        invocation.resolve(PendingActionResolution.approved("approval-1"));

        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "other.docx", "mode", "CREATE_NEW"))).isFalse();
        assertThat(invocation.consumeApproval(
                "file_write", Map.of("path", "report.docx", "mode", "OVERWRITE"))).isFalse();
        assertThat(invocation.consumeApproval(
                "file_read", Map.of("path", "report.docx", "mode", "CREATE_NEW"))).isFalse();
    }

    private static PendingAction action(
            String id, String toolName, Map<String, Object> arguments) {
        return new PendingAction(
                id, PendingActionType.HUMAN_APPROVAL,
                "approve", "approve tool",
                Map.of("toolName", toolName, "arguments", arguments));
    }
}
