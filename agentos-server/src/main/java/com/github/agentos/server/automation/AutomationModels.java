package com.github.agentos.server.automation;

import java.time.Instant;
import java.util.List;

/** 自动化任务、触发规则和执行历史的公共领域模型。 */
public final class AutomationModels {

    private AutomationModels() {
    }

    public enum TriggerType { PERIOD, INTERVAL }
    public enum PeriodMode { BASIC, CRON }
    public enum PeriodUnit { DAILY, WEEKLY, MONTHLY }
    public enum IntervalUnit { MINUTES, HOURS, DAYS }
    public enum TriggerSource { SCHEDULED, MANUAL }
    public enum ExecutionStatus {
        QUEUED, CLAIMED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED,
        SKIPPED_OFFLINE, SKIPPED_OVERLAP, SKIPPED_WORKSPACE_UNAVAILABLE
    }

    /** 带类型的调度定义；未使用的字段保持 null。 */
    public record TriggerDefinition(
            TriggerType type,
            PeriodMode periodMode,
            PeriodUnit periodUnit,
            String time,
            Integer weekday,
            Integer monthDay,
            String cron,
            Integer every,
            IntervalUnit intervalUnit,
            String timeZone) {
    }

    public record AutomationTask(
            String automationId,
            String teamId,
            String userId,
            String name,
            String prompt,
            String agentId,
            String modelId,
            String approvalMode,
            String desktopClientId,
            String workspaceId,
            String workspaceName,
            TriggerDefinition trigger,
            boolean enabled,
            Instant nextTriggerAt,
            Instant lastTriggerAt,
            Instant deletedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record SaveAutomationRequest(
            String name,
            String prompt,
            String modelId,
            String approvalMode,
            String desktopClientId,
            String workspaceId,
            String workspaceName,
            TriggerDefinition trigger,
            Boolean enabled) {
    }

    public record AutomationExecution(
            String executionId,
            String automationId,
            String teamId,
            String userId,
            String taskName,
            TriggerSource triggerSource,
            ExecutionStatus status,
            String scheduledKey,
            Instant scheduledAt,
            String desktopClientId,
            String workspaceId,
            String workspaceName,
            Instant claimedAt,
            Instant leaseExpiresAt,
            String sessionId,
            String runId,
            String invocationId,
            Instant startedAt,
            Instant finishedAt,
            String resultExcerpt,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record AutomationClient(
            String clientKey,
            String clientId,
            String teamId,
            String userId,
            String platform,
            String appVersion,
            Instant lastSeenAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SchedulePreview(String summary, List<Instant> nextOccurrences) {
    }

    public record ExecutionPage(
            List<AutomationExecution> items, long total, int offset, int limit, boolean hasMore) {
    }

    public record HeartbeatRequest(String clientId, String platform, String appVersion) {
    }

    public record ClaimRequest(String clientId) {
    }

    /** Tauri 构建的有界本地工作区上下文。 */
    public record WorkspaceContext(
            String name, List<String> tree, List<WorkspaceContextFile> files, boolean truncated) {
    }

    public record WorkspaceContextFile(
            String path, String content, boolean truncated, boolean mentioned) {
    }

    public record StartExecutionRequest(String clientId, WorkspaceContext workspaceContext) {
    }

    public record FailExecutionRequest(String clientId, String reason) {
    }
}
