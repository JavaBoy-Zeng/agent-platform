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
            @TableField("last_active_at") Instant lastActiveAt) {
    }

    @TableName("users")
    public record UserRow(
            @TableId String username,
            @TableField("password_hash") String passwordHash,
            String roles,
            @TableField("created_at") Instant createdAt) {
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
            boolean enabled,
            @TableField("last_status") String lastStatus,
            @TableField("last_error") String lastError,
            @TableField("last_checked_at") Instant lastCheckedAt,
            @TableField("created_at") Instant createdAt,
            @TableField("updated_at") Instant updatedAt,
            long version) {
    }

    @TableName("model_routes")
    public record ModelRouteRow(
            @TableId("route_key") String routeKey,
            @TableField("provider_id") String providerId,
            @TableField("model_id") String modelId,
            @TableField("updated_at") Instant updatedAt,
            long version) {
    }
}
