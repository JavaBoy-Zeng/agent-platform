package com.github.agentos.server.controller;

import com.github.agentos.kernel.trace.Span;
import com.github.agentos.kernel.trace.Trace;
import com.github.agentos.kernel.trace.TraceStore;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行链路追踪查询接口。
 *
 * <p>按 {@code invocationId} 查询单次执行的完整 Span 树，
 * 或按 {@code sessionId} 查询该会话下全部 Trace。</p>
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceStore traceStore;
    private final SessionAuthorization authorization;

    public TraceController(TraceStore traceStore, SessionAuthorization authorization) {
        this.traceStore = traceStore;
        this.authorization = authorization;
    }

    /** 按 invocationId 查询单条 Trace 的全部 Span。 */
    @GetMapping("/{invocationId}")
    public ResponseEntity<TraceView> trace(
            @PathVariable String invocationId, HttpServletRequest request) {
        List<Span> spans = traceStore.findByTraceId(invocationId);
        if (spans.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Trace trace = Trace.fromSpans(spans);
        authorization.requireOwned(trace.sessionId(), request);
        return ResponseEntity.ok(TraceView.from(trace));
    }

    /** 按 sessionId 查询该会话下全部 Trace，分组返回。 */
    @GetMapping
    public List<TraceView> traces(
            @RequestParam String sessionId, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        List<Span> spans = traceStore.findBySessionId(sessionId);
        return groupByTrace(spans).stream()
                .map(Trace::fromSpans)
                .map(TraceView::from)
                .toList();
    }

    /** 把同一 sessionId 的 Span 按 traceId 分组。 */
    private List<List<Span>> groupByTrace(List<Span> spans) {
        Map<String, List<Span>> grouped = new java.util.LinkedHashMap<>();
        for (Span span : spans) {
            grouped.computeIfAbsent(span.traceId(), k -> new java.util.ArrayList<>()).add(span);
        }
        return List.copyOf(grouped.values());
    }

    /** Trace REST 视图 DTO。 */
    public record TraceView(
            String traceId,
            String sessionId,
            String agentId,
            String startTime,
            String endTime,
            long durationMillis,
            int spanCount,
            int finishedSpanCount,
            List<SpanView> spans) {

        public static TraceView from(Trace trace) {
            return new TraceView(
                    trace.traceId(),
                    trace.sessionId(),
                    trace.agentId(),
                    trace.startTime() == null ? null : trace.startTime().toString(),
                    trace.endTime() == null ? null : trace.endTime().toString(),
                    trace.durationMillis(),
                    trace.spans().size(),
                    trace.finishedSpanCount(),
                    trace.spans().stream().map(SpanView::from).toList());
        }
    }

    /** Span REST 视图 DTO。 */
    public record SpanView(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            String start,
            String end,
            String status,
            long durationMillis,
            Map<String, Object> attributes,
            Map<String, Object> events) {

        public static SpanView from(Span span) {
            return new SpanView(
                    span.traceId(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.name(),
                    span.kind().name(),
                    span.start().toString(),
                    span.end() == null ? null : span.end().toString(),
                    span.status().name(),
                    span.durationMillis(),
                    span.attributes(),
                    span.events());
        }
    }
}
