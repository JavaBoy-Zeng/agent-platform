package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话结构化状态测试。 */
class SessionStateTest {

    @Test
    void startsEmptyAndGrowsWithValue() {
        SessionState state = SessionState.empty();

        assertThat(state.size()).isZero();
        assertThat(state.value("city")).isNull();

        SessionState withCity = state.withValue("city", "重庆");

        assertThat(withCity.value("city")).isEqualTo("重庆");
        assertThat(state.value("city")).isNull();
    }

    @Test
    void mergesDeltaWithLastWriteWins() {
        SessionState state = SessionState.empty()
                .withValue("unit", "celsius")
                .withValue("taskId", "T10001");

        SessionState merged = state.withDelta(Map.of("unit", "fahrenheit"));

        assertThat(merged.value("unit")).isEqualTo("fahrenheit");
        assertThat(merged.value("taskId")).isEqualTo("T10001");
        assertThat(merged.size()).isEqualTo(2);
    }

    @Test
    void readsTypedValuesWithFallbacks() {
        SessionState state = SessionState.of(Map.of(
                "turnCount", 3L,
                "name", "会话A",
                "bad", "not-a-number"));

        assertThat(state.longValue("turnCount", 0)).isEqualTo(3L);
        assertThat(state.longValue("bad", -1)).isEqualTo(-1);
        assertThat(state.longValue("missing", 7)).isEqualTo(7);
        assertThat(state.stringValue("name", "x")).isEqualTo("会话A");
        assertThat(state.stringValue("missing", "x")).isEqualTo("x");
    }

    @Test
    void parsesNumericStringAsLong() {
        SessionState state = SessionState.of(Map.of("turnCount", "5"));

        assertThat(state.longValue("turnCount", 0)).isEqualTo(5L);
    }

    @Test
    void snapshotIsImmutableCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("key", "value");
        SessionState state = SessionState.of(source);

        source.put("key", "mutated");

        assertThat(state.value("key")).isEqualTo("value");
        assertThat(state.asMap()).doesNotContainKey("other");
    }

    @Test
    void nullDeltaReturnsSameInstance() {
        SessionState state = SessionState.empty().withValue("k", "v");

        assertThat(state.withDelta(null)).isSameAs(state);
        assertThat(state.withDelta(Map.of())).isSameAs(state);
    }
}
