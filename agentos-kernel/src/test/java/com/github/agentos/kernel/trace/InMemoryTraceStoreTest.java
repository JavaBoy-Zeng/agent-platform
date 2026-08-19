package com.github.agentos.kernel.trace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTraceStoreTest {

    @Test
    void append_andFindByTraceId_returnsSortedSpans() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Instant t0 = Instant.now();
        Instant t1 = t0.plusMillis(10);
        Instant t2 = t0.plusMillis(20);

        Span root = new Span("trace-1", "s0", "", "root", Span.Kind.ROOT,
                t0, t2, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "a"), Map.of());
        Span child = new Span("trace-1", "s1", "s0", "model", Span.Kind.MODEL,
                t1, t2, Span.Status.OK, Map.of(), Map.of());

        store.append(child);
        store.append(root);

        List<Span> spans = store.findByTraceId("trace-1");
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).spanId()).isEqualTo("s0");
        assertThat(spans.get(1).spanId()).isEqualTo("s1");
    }

    @Test
    void findBySessionId_returnsAllTracesForSession() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Instant now = Instant.now();
        store.append(new Span("trace-1", "s1", "", "root", Span.Kind.ROOT,
                now, now, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "a"), Map.of()));
        store.append(new Span("trace-2", "s2", "", "root", Span.Kind.ROOT,
                now, now, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "b"), Map.of()));
        store.append(new Span("trace-3", "s3", "", "root", Span.Kind.ROOT,
                now, now, Span.Status.OK,
                Map.of("sessionId", "sess-2", "agentId", "c"), Map.of()));

        assertThat(store.findBySessionId("sess-1")).hasSize(2);
        assertThat(store.findBySessionId("sess-2")).hasSize(1);
        assertThat(store.findBySessionId("unknown")).isEmpty();
    }

    @Test
    void findByTraceId_unknownReturnsEmpty() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        assertThat(store.findByTraceId("nope")).isEmpty();
    }

    @Test
    void append_rejectsNull() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        assertThatThrownBy(() -> store.append(null)).isInstanceOf(NullPointerException.class);
    }
}
