package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Agent 领域事件的通用不可变实现，避免为每个生命周期节点创建独立类型。 */
public record DefaultAgentEvent(
        String eventId,
        String sessionId,
        String invocationId,
        String agentId,
        Instant timestamp,
        AgentEventType type,
        String message,
        Map<String, Object> data) implements AgentEvent {

    /** 复制并校验事件字段。 */
    public DefaultAgentEvent {
        eventId = requireText(eventId, "eventId");
        sessionId = requireText(sessionId, "sessionId");
        invocationId = requireText(invocationId, "invocationId");
        agentId = requireText(agentId, "agentId");
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 使用当前时间和随机事件标识创建领域事件。 */
    public static DefaultAgentEvent of(
            AgentContext context, AgentEventType type, String message, Map<String, Object> data) {
        Objects.requireNonNull(context, "context must not be null");
        if (context.invocation() == null) {
            throw new IllegalArgumentException("context must be bound to an invocation");
        }
        return new DefaultAgentEvent(
                UUID.randomUUID().toString(), context.sessionId(), context.invocationId(),
                context.agentId(), Instant.now(), type, message, data);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
