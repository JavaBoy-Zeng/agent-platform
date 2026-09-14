package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * AgentOS 对外唯一的流式事件信封。
 *
 * <p>模型厂商协议只能在模型适配层内出现；Runtime、持久化与客户端统一使用本协议。
 * {@code seq} 在单个 run 内严格递增，{@code eventId} 用于跨连接去重，
 * {@code itemId} 将 message、tool call 或 artifact 的完整生命周期关联起来。</p>
 */
public record AgentStreamEvent(
        String schemaVersion,
        String event,
        String eventId,
        String runId,
        String turnId,
        String sessionId,
        String itemId,
        String agentId,
        String parentRunId,
        long seq,
        Instant timestamp,
        Visibility visibility,
        Map<String, Object> data) {

    public static final String SCHEMA_VERSION = "1";

    public AgentStreamEvent {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        event = requireText(event, "event");
        eventId = requireText(eventId, "eventId");
        runId = requireText(runId, "runId");
        turnId = requireText(turnId, "turnId");
        sessionId = requireText(sessionId, "sessionId");
        itemId = requireText(itemId, "itemId");
        agentId = requireText(agentId, "agentId");
        parentRunId = parentRunId == null ? "" : parentRunId.trim();
        if (seq <= 0) throw new IllegalArgumentException("seq must be positive");
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 只有 USER 事件可以进入 SSE；INTERNAL 事件仅供服务端诊断。 */
    public enum Visibility {
        USER,
        INTERNAL
    }

    /** 第一版稳定事件名。 */
    public enum Type {
        RUN_STARTED("run.started", true),
        RUN_WAITING("run.waiting", true),
        RUN_COMPLETED("run.completed", true),
        RUN_FAILED("run.failed", true),
        RUN_CANCELLED("run.cancelled", true),
        STATUS("status", false),
        MESSAGE_STARTED("message.started", true),
        MESSAGE_DELTA("message.delta", false),
        MESSAGE_COMPLETED("message.completed", true),
        TOOL_STARTED("tool.started", true),
        TOOL_INPUT_DELTA("tool.input.delta", false),
        TOOL_AWAITING_APPROVAL("tool.awaiting_approval", true),
        TOOL_APPROVED("tool.approved", true),
        TOOL_COMPLETED("tool.completed", true),
        TOOL_FAILED("tool.failed", true),
        ARTIFACT_CREATED("artifact.created", true),
        USAGE("usage", true),
        ERROR("error", true);

        private final String wireName;
        private final boolean durable;

        Type(String wireName, boolean durable) {
            this.wireName = wireName;
            this.durable = durable;
        }

        public String wireName() { return wireName; }
        public boolean durable() { return durable; }

        public static Type fromWireName(String value) {
            for (Type type : values()) {
                if (type.wireName.equals(value)) return type;
            }
            throw new IllegalArgumentException("unknown Agent stream event: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
