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
        Map<String, Object> data,
        EventActions actions) implements AgentEvent {

    /** 兼容不带动作的九字段构造。 */
    public DefaultAgentEvent(
            String eventId,
            String sessionId,
            String invocationId,
            String agentId,
            Instant timestamp,
            AgentEventType type,
            String message,
            Map<String, Object> data) {
        this(eventId, sessionId, invocationId, agentId, timestamp, type, message, data,
                EventActions.NONE);
    }

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
        actions = actions == null ? EventActions.NONE : actions;
    }

    /** 使用当前时间和随机事件标识创建无指令领域事件。 */
    public static DefaultAgentEvent of(
            InvocationContext context, AgentEventType type, String message, Map<String, Object> data) {
        return of(context, type, message, data, EventActions.NONE);
    }

    /** 使用当前时间和随机事件标识创建携带动作的领域事件。 */
    public static DefaultAgentEvent of(
            InvocationContext context, AgentEventType type, String message,
            Map<String, Object> data, EventActions actions) {
        Objects.requireNonNull(context, "context must not be null");
        if (context.invocation() == null) {
            throw new IllegalArgumentException("context must be bound to an invocation");
        }
        return new DefaultAgentEvent(
                UUID.randomUUID().toString(), context.sessionId(), context.invocationId(),
                context.agentId(), Instant.now(), type, message, data, actions);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
