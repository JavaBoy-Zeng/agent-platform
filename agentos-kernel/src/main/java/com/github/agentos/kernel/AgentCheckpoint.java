package com.github.agentos.kernel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 可跨进程恢复的一次 Invocation 执行快照。 */
public record AgentCheckpoint(
        String sessionId,
        String invocationId,
        String agentId,
        String taskId,
        String teamId,
        String userId,
        String objective,
        String currentPlanId,
        String currentStepId,
        int currentStepIndex,
        List<String> completedStepIds,
        Map<String, String> state,
        PendingAction pendingAction,
        ExecutionCounters executionCounters,
        AgentRunStatus status,
        Instant savedAt) {

    /** 复制集合并校验快照身份和状态。 */
    public AgentCheckpoint {
        sessionId = requireText(sessionId, "sessionId");
        invocationId = requireText(invocationId, "invocationId");
        agentId = requireText(agentId, "agentId");
        taskId = taskId == null ? "" : taskId;
        teamId = requireText(teamId, "teamId");
        userId = requireText(userId, "userId");
        objective = requireText(objective, "objective");
        currentPlanId = currentPlanId == null ? "" : currentPlanId;
        currentStepId = currentStepId == null ? "" : currentStepId;
        if (currentStepIndex < 0) {
            throw new IllegalArgumentException("currentStepIndex must not be negative");
        }
        completedStepIds = completedStepIds == null ? List.of() : List.copyOf(completedStepIds);
        state = state == null ? Map.of() : Map.copyOf(state);
        executionCounters = java.util.Objects.requireNonNull(
                executionCounters, "executionCounters must not be null");
        status = java.util.Objects.requireNonNull(status, "status must not be null");
        savedAt = java.util.Objects.requireNonNull(savedAt, "savedAt must not be null");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
