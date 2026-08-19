package com.github.agentos.kernel.trace;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一次不可变的执行片段（Span）。
 *
 * <p>一个 Span 描述一次有时间边界的操作：Agent 运行、模型调用、工具调用或
 * 计划步骤。同一 {@link #traceId()} 的 Span 集合构成一条 Trace。
 * 通过 {@link #parentSpanId()} 描述父子层级，根 Span 的 parentSpanId 为空。</p>
 *
 * @param traceId    所属 Trace 标识（通常为 invocationId）
 * @param spanId     本 Span 的唯一标识
 * @param parentSpanId 父 Span 标识；根 Span 为空字符串
 * @param name       Span 名称（通常为事件类型或工具/模型名）
 * @param kind       Span 类别
 * @param start      开始时间
 * @param end        结束时间；未结束时为 null
 * @param status     执行状态
 * @param attributes 结构化属性
 * @param events     关联的轻量事件日志（name + timestamp）
 */
public record Span(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        Kind kind,
        Instant start,
        Instant end,
        Status status,
        Map<String, Object> attributes,
        Map<String, Object> events) {

    /** 创建并校验 Span；attributes 与 events 被复制为不可变 Map。 */
    public Span {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(start, "start must not be null");
        end = end == null ? null : end;
        status = status == null ? Status.ACTIVE : status;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        events = events == null ? Map.of() : Map.copyOf(events);
    }

    /** Span 类别。 */
    public enum Kind {
        /** 根 Span：一次完整 Invocation。 */
        ROOT,
        /** 模型调用。 */
        MODEL,
        /** 工具调用。 */
        TOOL,
        /** 计划步骤。 */
        STEP,
        /** 其他内部操作。 */
        INTERNAL
    }

    /** Span 执行状态。 */
    public enum Status {
        /** 已开始但未结束。 */
        ACTIVE,
        /** 成功结束。 */
        OK,
        /** 失败结束。 */
        ERROR,
        /** 被取消。 */
        CANCELLED
    }

    /** 返回 Span 是否已结束。 */
    public boolean isFinished() {
        return end != null && status != Status.ACTIVE;
    }

    /** 返回 Span 持续时长（毫秒）；未结束时返回 -1。 */
    public long durationMillis() {
        if (end == null) {
            return -1;
        }
        return end.toEpochMilli() - start.toEpochMilli();
    }
}
