package com.github.agentos.server.settings;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置对象编解码与默认值测试。 */
class NonAdminCallLimitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultsAreEnabledWithFiveCallsPerHalfHour() {
        NonAdminCallLimit defaults = NonAdminCallLimit.defaults();
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.maxCalls()).isEqualTo(5);
        assertThat(defaults.windowSeconds()).isEqualTo(1800L);
        assertThat(defaults.windowDuration()).hasSeconds(1800L);
    }

    @Test
    void encodeAndDecodeRoundTrip() {
        NonAdminCallLimit value = new NonAdminCallLimit(true, 3, 600L);
        String json = value.toJson(objectMapper);
        NonAdminCallLimit decoded = NonAdminCallLimit.fromJson(json, objectMapper);
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void decodingDisabledLimitRestoresZeroes() {
        NonAdminCallLimit disabled = NonAdminCallLimit.DISABLED;
        String json = disabled.toJson(objectMapper);
        NonAdminCallLimit decoded = NonAdminCallLimit.fromJson(json, objectMapper);
        assertThat(decoded.enabled()).isFalse();
        assertThat(decoded.maxCalls()).isEqualTo(0);
        assertThat(decoded.windowSeconds()).isEqualTo(0L);
    }

    @Test
    void blankJsonIsDisabled() {
        assertThat(NonAdminCallLimit.fromJson("", objectMapper).enabled()).isFalse();
        assertThat(NonAdminCallLimit.fromJson(null, objectMapper).enabled()).isFalse();
    }

    @Test
    void enabledButZeroMaxCallsRejected() {
        assertThatThrownBy(() -> new NonAdminCallLimit(true, 0, 60L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCalls");
    }

    @Test
    void enabledButZeroWindowRejected() {
        assertThatThrownBy(() -> new NonAdminCallLimit(true, 1, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSeconds");
    }

    @Test
    void orDefaultFillsMissingFields() {
        NonAdminCallLimit partial = new NonAdminCallLimit(false, 0, 0L);
        NonAdminCallLimit filled = partial.orDefault();
        assertThat(filled.enabled()).isFalse();
        // 显式禁用时不应用默认次数/窗口，但仍保证非零值，便于读取展示。
        assertThat(filled.maxCalls()).isEqualTo(5);
        assertThat(filled.windowSeconds()).isEqualTo(1800L);
    }
}