package com.github.agentos.server.settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Objects;

/**
 * 非管理员调用默认模型的限频配置。
 *
 * <p>{@code enabled=false} 时不限频；否则在 {@code windowSeconds} 滚动窗口内
 * 限制 {@code maxCalls} 次调用。窗口用秒数持久化，避免依赖 Jackson 的
 * JSR-310 模块；调用方按需用 {@link #windowDuration()} 取回 {@link Duration}。</p>
 */
public record NonAdminCallLimit(boolean enabled, int maxCalls, long windowSeconds) {

    public static final NonAdminCallLimit DISABLED =
            new NonAdminCallLimit(false, 0, 0L);

    /** 默认策略：每用户每 30 分钟最多 5 次。 */
    public static NonAdminCallLimit defaults() {
        return new NonAdminCallLimit(true, 5, 1800L);
    }

    public NonAdminCallLimit {
        if (enabled) {
            if (maxCalls <= 0) {
                throw new IllegalArgumentException("maxCalls must be positive when enabled");
            }
            if (windowSeconds <= 0L) {
                throw new IllegalArgumentException("windowSeconds must be positive when enabled");
            }
        }
    }

    public Duration windowDuration() {
        return Duration.ofSeconds(windowSeconds);
    }

    /** 与默认策略合并：禁用态必须显式传入，启用态的字段缺失时回退到 defaults()。 */
    public NonAdminCallLimit orDefault() {
        NonAdminCallLimit d = defaults();
        return new NonAdminCallLimit(
                enabled,
                maxCalls > 0 ? maxCalls : d.maxCalls,
                windowSeconds > 0L ? windowSeconds : d.windowSeconds);
    }

    public String toJson(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JacksonException exception) {
            throw new IllegalStateException("encode NonAdminCallLimit failed", exception);
        }
    }

    public static NonAdminCallLimit fromJson(String text, ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (text == null || text.isBlank()) return DISABLED;
        try {
            return objectMapper.readValue(text, NonAdminCallLimit.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "settings.non-admin-call-limit JSON 解析失败: " + exception.getMessage(),
                    exception);
        }
    }
}