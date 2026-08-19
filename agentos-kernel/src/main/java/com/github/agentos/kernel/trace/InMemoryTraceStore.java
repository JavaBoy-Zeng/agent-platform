package com.github.agentos.kernel.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 {@link TraceStore}，按 traceId 与 sessionId 分桶存储 Span。
 *
 * <p>线程安全，Span 按追加顺序保留；查询时按 start 时间排序返回。</p>
 */
public final class InMemoryTraceStore implements TraceStore {

    private final Map<String, List<Span>> byTraceId = new ConcurrentHashMap<>();
    private final Map<String, List<Span>> bySessionId = new ConcurrentHashMap<>();

    @Override
    public void append(Span span) {
        Objects.requireNonNull(span, "span must not be null");
        byTraceId.computeIfAbsent(span.traceId(), k -> new CopyOnWriteArrayList<>()).add(span);
        String sessionId = span.attributes().getOrDefault("sessionId", "").toString();
        if (!sessionId.isEmpty()) {
            bySessionId.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(span);
        }
    }

    @Override
    public List<Span> findByTraceId(String traceId) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        return sorted(byTraceId.getOrDefault(traceId, List.of()));
    }

    @Override
    public List<Span> findBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return sorted(bySessionId.getOrDefault(sessionId, List.of()));
    }

    /** 按 start 时间排序返回不可变副本。 */
    private List<Span> sorted(List<Span> spans) {
        List<Span> copy = new ArrayList<>(spans);
        copy.sort(Comparator.comparing(Span::start));
        return List.copyOf(copy);
    }
}
