package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 运行过程中可供日志、SSE 或其他观察端消费的不可变事件。
 *
 * @param type 事件类型
 * @param sessionId 会话标识
 * @param message 面向人的简短说明
 * @param data 结构化事件数据；不得放入无限长度的原始工具输出
 * @param occurredAt 事件产生时间
 */
public record AgentRunEvent(
        Type type,
        String sessionId,
        String message,
        Map<String, Object> data,
        Instant occurredAt) {

    /** 创建并复制事件数据。 */
    public AgentRunEvent {
        type = Objects.requireNonNull(type, "type must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /** 使用当前时间创建事件。 */
    public static AgentRunEvent of(
            Type type, String sessionId, String message, Map<String, Object> data) {
        return new AgentRunEvent(type, sessionId, message, data, Instant.now());
    }

    /** Agent 主链路中的可观察阶段。 */
    public enum Type {
        RUN_STARTED,
        PLAN_CREATED,
        TOOL_STARTED,
        TOOL_FINISHED,
        OBSERVATION,
        DECISION,
        REPLAN,
        OUTPUT_DELTA,
        RUN_COMPLETED,
        RUN_CANCELLED,
        RUN_FAILED
    }
}
