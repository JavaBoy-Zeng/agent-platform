package com.github.agentos.server.model;

import com.github.agentos.server.settings.NonAdminCallLimit;
import com.github.agentos.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 非管理员调用默认模型的滑动窗口限频器。
 *
 * <p>策略从 {@link SettingsService#NON_ADMIN_CALL_LIMIT} 实时读取，
 * 管理员修改后下一次调用立即按新策略判定；缓存时长 1 秒以避免高频读 DB。</p>
 */
@Component
public final class NonAdminCallLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonAdminCallLimiter.class);

    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long cacheNanos;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicLong cachedExpiresAt = new AtomicLong();
    private volatile NonAdminCallLimit cachedLimit = NonAdminCallLimit.DISABLED;

    @Autowired
    public NonAdminCallLimiter(SettingsService settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, Clock.systemUTC(), 1_000_000_000L);
    }

    /** 测试可注入 fake clock 与较短缓存时间。 */
    NonAdminCallLimiter(
            SettingsService settings, ObjectMapper objectMapper, Clock clock, long cacheNanos) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.cacheNanos = cacheNanos;
    }

    /**
     * 记录一次调用并判定是否超限。
     *
     * @return {@link Decision#allow()} 表示放行；{@link Decision#deny(reason)}
     *         表示拒绝并附带错误原因（HTTP 429 等）。
     */
    public Decision check(String userId) {
        if (userId == null || userId.isBlank()) return Decision.allow();
        NonAdminCallLimit limit = currentLimit();
        if (!limit.enabled()) return Decision.allow();
        long now = clock.millis();
        long windowMillis = limit.windowSeconds() * 1000L;
        Deque<Long> stamps = windows.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (stamps) {
            long cutoff = now - windowMillis;
            while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                stamps.pollFirst();
            }
            if (stamps.size() >= limit.maxCalls()) {
                long retryAfterMillis = (stamps.peekFirst() + windowMillis) - now;
                String message = "非管理员调用默认模型的频率超过 "
                        + limit.maxCalls() + " 次 / " + limit.windowSeconds() + " 秒";
                LOGGER.info("[rate-limit] deny user={} used={} window={}ms retryAfter={}ms",
                        userId, stamps.size(), windowMillis, retryAfterMillis);
                return Decision.deny(message, retryAfterMillis);
            }
            stamps.addLast(now);
            return Decision.allow();
        }
    }

    private NonAdminCallLimit currentLimit() {
        long now = clock.millis();
        long expiresAt = cachedExpiresAt.get();
        if (now < expiresAt) return cachedLimit;
        // 未配置视为关闭：admin 必须在 UI 里显式开启后才会限频，避免静默卡住非管理员调用。
        NonAdminCallLimit next = settings.read(SettingsService.NON_ADMIN_CALL_LIMIT)
                .map(text -> NonAdminCallLimit.fromJson(text, objectMapper))
                .orElse(NonAdminCallLimit.DISABLED);
        cachedLimit = next;
        cachedExpiresAt.set(now + cacheNanos);
        return next;
    }

    /** 限频判定结果。 */
    public record Decision(boolean allowed, String reason, long retryAfterMillis) {
        public static Decision allow() {
            return new Decision(true, "", 0L);
        }
        public static Decision deny(String reason, long retryAfterMillis) {
            return new Decision(false, reason == null ? "" : reason,
                    Math.max(0L, retryAfterMillis));
        }
    }
}