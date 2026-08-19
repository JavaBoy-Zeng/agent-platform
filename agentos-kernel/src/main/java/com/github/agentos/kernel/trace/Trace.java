package com.github.agentos.kernel.trace;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一条完整的调用链（Trace）。
 *
 * <p>由同一 {@link #traceId()} 的全部 {@link Span} 按时间顺序组成，
 * 描述一次 Invocation 的完整执行轨迹。根 Span 记录整次运行的起止，
 * 子 Span 记录模型调用、工具调用、计划步骤等。</p>
 *
 * @param traceId     Trace 标识（通常为 invocationId）
 * @param sessionId   所属会话标识
 * @param agentId     执行 Agent 标识
 * @param spans       按开始时间排序的 Span 列表
 * @param startTime   Trace 起始时间（根 Span 的 start）
 * @param endTime     Trace 结束时间（根 Span 的 end；未结束时为 null）
 */
public record Trace(
        String traceId,
        String sessionId,
        String agentId,
        List<Span> spans,
        Instant startTime,
        Instant endTime) {

    /** 创建并校验 Trace；spans 被复制为不可变列表。 */
    public Trace {
        Objects.requireNonNull(traceId, "traceId must not be null");
        sessionId = sessionId == null ? "" : sessionId;
        agentId = agentId == null ? "" : agentId;
        spans = spans == null ? List.of() : List.copyOf(spans);
        startTime = startTime == null ? (spans.isEmpty() ? Instant.now() : spans.get(0).start()) : startTime;
        endTime = endTime;
    }

    /** 从 Span 列表聚合为 Trace；traceId/sessionId/agentId 取自根 Span。 */
    public static Trace fromSpans(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return new Trace("", "", "", List.of(), null, null);
        }
        Span root = spans.stream()
                .filter(s -> s.parentSpanId().isEmpty())
                .findFirst()
                .orElse(spans.get(0));
        return new Trace(
                root.traceId(),
                root.attributes().getOrDefault("sessionId", "").toString(),
                root.attributes().getOrDefault("agentId", "").toString(),
                spans,
                root.start(),
                root.end());
    }

    /** 返回 Trace 中已完成的 Span 数量。 */
    public int finishedSpanCount() {
        return (int) spans.stream().filter(Span::isFinished).count();
    }

    /** 返回 Trace 的总持续时长（毫秒）；未结束时返回 -1。 */
    public long durationMillis() {
        if (endTime == null) {
            return -1;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }
}
