package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.AgentStreamEvent;
import com.github.agentos.kernel.PendingAction;

import java.time.Instant;

/** 可持久化、可在终态事件中完整返回的 Run 状态快照。 */
public record AgentRunSnapshot(
        String schemaVersion,
        String runId,
        String turnId,
        String parentRunId,
        String sessionId,
        String agentId,
        String userId,
        String invocationId,
        AgentRunStatus status,
        PendingAction pendingAction,
        long lastSeq,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        long durationMs,
        String output,
        AgentRunError error,
        AgentRunUsage usage) {

    public AgentRunSnapshot {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? AgentStreamEvent.SCHEMA_VERSION : schemaVersion.trim();
        parentRunId = parentRunId == null ? "" : parentRunId.trim();
        userId = userId == null ? "" : userId.trim();
        invocationId = invocationId == null ? "" : invocationId.trim();
        output = output == null ? "" : output;
        usage = usage == null ? AgentRunUsage.ZERO : usage;
    }
}
