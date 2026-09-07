package com.github.agentos.server.model;

import com.github.agentos.server.settings.NonAdminCallLimit;
import com.github.agentos.server.settings.SettingsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 限频器滑动窗口判定测试。 */
class NonAdminCallLimiterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledLimitNeverDenies() {
        SettingsService settings = new SettingsService.InMemorySettingsService();
        NonAdminCallLimiter limiter = new NonAdminCallLimiter(
                settings, objectMapper, fakeClock(Instant.now()), 1L);
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.check("alice").allowed()).isTrue();
        }
    }

    @Test
    void unconfiguredLimitIsTreatedAsDisabled() {
        SettingsService settings = new SettingsService.InMemorySettingsService();
        NonAdminCallLimiter limiter = new NonAdminCallLimiter(
                settings, objectMapper, fakeClock(Instant.now()), 1L);
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.check("alice").allowed()).isTrue();
        }
    }

    @Test
    void allowsUpToMaxThenDeniesUntilWindowSlides() {
        SettingsService settings = new SettingsService.InMemorySettingsService();
        settings.write(SettingsService.NON_ADMIN_CALL_LIMIT,
                new NonAdminCallLimit(true, 3, 600L).toJson(objectMapper),
                "admin");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        NonAdminCallLimiter limiter = new NonAdminCallLimiter(
                settings, objectMapper, new ManualClock(now), 1L);

        assertThat(limiter.check("alice").allowed()).isTrue();
        assertThat(limiter.check("alice").allowed()).isTrue();
        assertThat(limiter.check("alice").allowed()).isTrue();
        NonAdminCallLimiter.Decision fourth = limiter.check("alice");
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.reason()).contains("频率");
        assertThat(fourth.retryAfterMillis()).isPositive();

        // 推进 11 分钟，超过 10 分钟窗口，前三次调用已过期，新一次放行
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(limiter.check("alice").allowed()).isTrue();
    }

    @Test
    void differentUsersHaveIndependentBuckets() {
        SettingsService settings = new SettingsService.InMemorySettingsService();
        settings.write(SettingsService.NON_ADMIN_CALL_LIMIT,
                new NonAdminCallLimit(true, 1, 600L).toJson(objectMapper),
                "admin");
        NonAdminCallLimiter limiter = new NonAdminCallLimiter(
                settings, objectMapper, fakeClock(Instant.now()), 1L);

        assertThat(limiter.check("alice").allowed()).isTrue();
        assertThat(limiter.check("alice").allowed()).isFalse();
        assertThat(limiter.check("bob").allowed()).isTrue();
    }

    @Test
    void blankUserIdIsAlwaysAllowed() {
        SettingsService settings = new SettingsService.InMemorySettingsService();
        settings.write(SettingsService.NON_ADMIN_CALL_LIMIT,
                new NonAdminCallLimit(true, 1, 600L).toJson(objectMapper),
                "admin");
        NonAdminCallLimiter limiter = new NonAdminCallLimiter(
                settings, objectMapper, fakeClock(Instant.now()), 1L);

        assertThat(limiter.check(null).allowed()).isTrue();
        assertThat(limiter.check("").allowed()).isTrue();
    }

    private static Clock fakeClock(Instant now) {
        return Clock.fixed(now, ZoneId.systemDefault());
    }

    /** 可手动推进的 Clock，用于窗口滑动测试。 */
    private static final class ManualClock extends Clock {
        private final AtomicReference<Instant> now;

        ManualClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}