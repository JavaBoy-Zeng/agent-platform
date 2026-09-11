package com.github.agentos.kernel.trace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceTest {

    @Test
    void fromSpans_buildsFromRootSpan() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(200);
        Span root = new Span("trace-1", "span-root", "", "agent-run",
                Span.Kind.ROOT, start, end, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "plan-execute-agent"), Map.of());
        Span child = new Span("trace-1", "span-child", "span-root", "model-call",
                Span.Kind.MODEL, start.plusMillis(50), start.plusMillis(100),
                Span.Status.OK, Map.of(), Map.of());

        Trace trace = Trace.fromSpans(List.of(child, root));

        assertThat(trace.traceId()).isEqualTo("trace-1");
        assertThat(trace.sessionId()).isEqualTo("sess-1");
        assertThat(trace.agentId()).isEqualTo("plan-execute-agent");
        assertThat(trace.spans()).hasSize(2);
        assertThat(trace.startTime()).isEqualTo(start);
        assertThat(trace.endTime()).isEqualTo(end);
        assertThat(trace.durationMillis()).isGreaterThanOrEqualTo(0);
        assertThat(trace.finishedSpanCount()).isEqualTo(2);
    }

    @Test
    void fromSpans_emptyListReturnsEmptyTrace() {
        Trace trace = Trace.fromSpans(List.of());
        assertThat(trace.traceId()).isEmpty();
        assertThat(trace.spans()).isEmpty();
        assertThat(trace.finishedSpanCount()).isZero();
        assertThat(trace.durationMillis()).isEqualTo(-1);
    }

    @Test
    void durationMillis_negativeWhenUnfinished() {
        Span root = new Span("t1", "s1", "", "root", Span.Kind.ROOT,
                Instant.now(), null, Span.Status.ACTIVE, Map.of(), Map.of());
        Trace trace = Trace.fromSpans(List.of(root));
        assertThat(trace.endTime()).isNull();
        assertThat(trace.durationMillis()).isEqualTo(-1);
    }

    @Test
    void fromSpans_usesFirstSpanWhenNoRootFound() {
        Instant start = Instant.now();
        Span only = new Span("t1", "s1", "parent", "orphan", Span.Kind.TOOL,
                start, start.plusMillis(10), Span.Status.OK, Map.of(), Map.of());
        Trace trace = Trace.fromSpans(List.of(only));
        assertThat(trace.startTime()).isEqualTo(start);
        assertThat(trace.spans()).hasSize(1);
    }
}
