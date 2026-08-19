package com.github.agentos.kernel.trace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanTest {

    @Test
    void defaults_whenEndAndStatusOmitted() {
        Span span = new Span("trace-1", "span-1", "", "root",
                Span.Kind.ROOT, Instant.now(), null, null, null, null);
        assertThat(span.end()).isNull();
        assertThat(span.status()).isEqualTo(Span.Status.ACTIVE);
        assertThat(span.parentSpanId()).isEmpty();
        assertThat(span.attributes()).isEmpty();
        assertThat(span.events()).isEmpty();
        assertThat(span.isFinished()).isFalse();
        assertThat(span.durationMillis()).isEqualTo(-1);
    }

    @Test
    void isFinished_trueWhenEndAndTerminalStatus() {
        Span span = new Span("t1", "s1", "", "root", Span.Kind.ROOT,
                Instant.now(), Instant.now().plusMillis(100), Span.Status.OK, Map.of(), Map.of());
        assertThat(span.isFinished()).isTrue();
        assertThat(span.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void rejectsNullRequiredFields() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new Span(null, "s1", "", "n", Span.Kind.ROOT, now, null, Span.Status.OK, Map.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Span("t1", null, "", "n", Span.Kind.ROOT, now, null, Span.Status.OK, Map.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Span("t1", "s1", "", null, Span.Kind.ROOT, now, null, Span.Status.OK, Map.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copiesAttributesAndEvents() {
        Map<String, Object> attrs = new java.util.HashMap<>(Map.of("key", "value"));
        Map<String, Object> events = new java.util.HashMap<>(Map.of("now", "event1"));
        Span span = new Span("t1", "s1", "", "n", Span.Kind.TOOL,
                Instant.now(), Instant.now(), Span.Status.OK, attrs, events);
        attrs.put("new", "should-not-leak");
        assertThat(span.attributes()).hasSize(1).containsEntry("key", "value");
        assertThat(span.events()).hasSize(1);
    }

    @Test
    void allKindsAndStatusesAreUsable() {
        for (Span.Kind kind : Span.Kind.values()) {
            for (Span.Status status : Span.Status.values()) {
                Span span = new Span("t", "s", "", "n", kind,
                        Instant.now(), status == Span.Status.ACTIVE ? null : Instant.now(),
                        status, Map.of(), Map.of());
                assertThat(span.kind()).isEqualTo(kind);
                assertThat(span.status()).isEqualTo(status);
            }
        }
    }
}
