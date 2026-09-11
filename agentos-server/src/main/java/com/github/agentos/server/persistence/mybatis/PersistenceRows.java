package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** MyBatis-Plus 使用的 PostgreSQL 行模型。 */
public final class PersistenceRows {

    private PersistenceRows() {
    }

    @TableName("agent_events")
    public record AgentEventRow(
            @TableId("event_id") String eventId,
            @TableField("session_id") String sessionId,
            @TableField("invocation_id") String invocationId,
            @TableField("agent_id") String agentId,
            @TableField("occurred_at") Instant occurredAt,
            @TableField("event_type") String eventType,
            String message,
            @TableField("event_data") String eventData,
            @TableField("event_actions") String eventActions) {
    }

    @TableName("agent_runs")
    public record AgentRunRow(
            @TableId("run_id") String runId,
            @TableField("session_id") String sessionId,
            @TableField("user_id") String userId,
            String status,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt,
            @TableField("snapshot_payload") String snapshotPayload) {
    }

    @TableName("agent_stream_events")
    public record AgentStreamEventRow(
            @TableId("event_id") String eventId,
            @TableField("schema_version") String schemaVersion,
            @TableField("event_name") String eventName,
            @TableField("run_id") String runId,
            @TableField("turn_id") String turnId,
            @TableField("session_id") String sessionId,
            @TableField("item_id") String itemId,
            @TableField("agent_id") String agentId,
            @TableField("parent_run_id") String parentRunId,
            @TableField("event_seq") long eventSeq,
            @TableField("occurred_at") Instant occurredAt,
            String visibility,
            @TableField("event_data") String eventData) {
    }

    @TableName("agent_checkpoints")
    public record CheckpointRow(
            @TableId("invocation_id") String invocationId,
            String payload,
            @TableField("saved_at") Instant savedAt) {
    }

    @TableName("agent_continuations")
    public record ContinuationRow(
            @TableId("invocation_id") String invocationId,
            String payload,
            @TableField("saved_at") Instant savedAt) {
    }

    @TableName("session_usage")
    public record UsageRow(
            @TableId("session_id") String sessionId,
            @TableField("model_calls") long modelCalls,
            @TableField("prompt_tokens") long promptTokens,
            @TableField("completion_tokens") long completionTokens,
            @TableField("updated_at") Instant updatedAt) {
    }

    @TableName("agent_sessions")
    public record SessionRow(
            @TableId("session_id") String sessionId,
            @TableField("user_id") String userId,
            @TableField("state_payload") String statePayload,
            @TableField("created_at") Instant createdAt,
            @TableField("last_active_at") Instant lastActiveAt,
            @TableField("deleted_at") Instant deletedAt) {
    }

    @TableName("users")
    public record UserRow(
            @TableId String username,
            @TableField("password_hash") String passwordHash,
            String roles,
            @TableField("created_at") Instant createdAt) {
    }

    @TableName("settings")
    public record SettingRow(
            @TableId("setting_key") String settingKey,
            String settingValue,
            @TableField("updated_at") Instant updatedAt,
            @TableField("updated_by") String updatedBy) {
    }

    @TableName("memory_records")
    public record MemoryRecordRow(
            @TableId("record_key") String recordKey,
            @TableField("record_kind") String recordKind,
            @TableField("business_id") String businessId,
            @TableField("team_id") String teamId,
            @TableField("user_id") String userId,
            @TableField("agent_id") String agentId,
            @TableField("session_id") String sessionId,
            @TableField("task_id") String taskId,
            @TableField("record_version") int recordVersion,
            @TableField("record_status") String recordStatus,
            @TableField("sort_at") Instant sortAt,
            @TableField("expires_at") Instant expiresAt,
            String payload,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt) {
    }

    @TableName("model_providers")
    public record ModelProviderRow(
            @TableId("provider_id") String providerId,
            @TableField("display_name") String displayName,
            @TableField("provider_type") String providerType,
            String protocol,
            String endpoint,
            @TableField("encrypted_api_key") String encryptedApiKey,
            @TableField("models_payload") String modelsPayload,
            @TableField("default_model") String defaultModel,
            @TableField("response_format") String responseFormat,
            @TableField("reasoning_split") boolean reasoningSplit,
            @TableField("settings_payload") String settingsPayload,
            boolean enabled,
            @TableField("last_status") String lastStatus,
            @TableField("last_error") String lastError,
            @TableField("last_checked_at") Instant lastCheckedAt,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt,
            long version) {
    }

    @TableName("automation_tasks")
    public record AutomationTaskRow(
            @TableId("automation_id") String automationId,
            @TableField("team_id") String teamId,
            @TableField("user_id") String userId,
            String name,
            String prompt,
            @TableField("agent_id") String agentId,
            @TableField("model_id") String modelId,
            @TableField("approval_mode") String approvalMode,
            @TableField("desktop_client_id") String desktopClientId,
            @TableField("workspace_id") String workspaceId,
            @TableField("workspace_name") String workspaceName,
            @TableField("trigger_payload") String triggerPayload,
            boolean enabled,
            @TableField("next_trigger_at") Instant nextTriggerAt,
            @TableField("last_trigger_at") Instant lastTriggerAt,
            @TableField("deleted_at") Instant deletedAt,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt,
            long version) {
    }

    @TableName("automation_executions")
    public record AutomationExecutionRow(
            @TableId("execution_id") String executionId,
            @TableField("automation_id") String automationId,
            @TableField("team_id") String teamId,
            @TableField("user_id") String userId,
            @TableField("task_name") String taskName,
            @TableField("trigger_source") String triggerSource,
            String status,
            @TableField("scheduled_key") String scheduledKey,
            @TableField("scheduled_at") Instant scheduledAt,
            @TableField("desktop_client_id") String desktopClientId,
            @TableField("workspace_id") String workspaceId,
            @TableField("workspace_name") String workspaceName,
            @TableField("claimed_at") Instant claimedAt,
            @TableField("lease_expires_at") Instant leaseExpiresAt,
            @TableField("session_id") String sessionId,
            @TableField("run_id") String runId,
            @TableField("invocation_id") String invocationId,
            @TableField("started_at") Instant startedAt,
            @TableField("finished_at") Instant finishedAt,
            @TableField("result_excerpt") String resultExcerpt,
            @TableField("error_message") String errorMessage,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt,
            long version) {
    }

    @TableName("automation_clients")
    public record AutomationClientRow(
            @TableId("client_key") String clientKey,
            @TableField("client_id") String clientId,
            @TableField("team_id") String teamId,
            @TableField("user_id") String userId,
            String platform,
            @TableField("app_version") String appVersion,
            @TableField("last_seen_at") Instant lastSeenAt,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt) {
    }

}
