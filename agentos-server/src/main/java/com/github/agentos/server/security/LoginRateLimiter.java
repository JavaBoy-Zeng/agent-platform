package com.github.agentos.server.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限速器：同一 key（用户名+来源）连续失败达到阈值后锁定一段时间。
 *
 * <p>进程内实现，重启即清零；对单实例个人部署足够。</p>
 */
public final class LoginRateLimiter {

    private final int maxFailures;
    private final Duration lockout;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** 创建限速器。 */
    public LoginRateLimiter(int maxFailures, Duration lockout) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        Objects.requireNonNull(lockout, "lockout must not be null");
        if (lockout.isNegative() || lockout.isZero()) {
            throw new IllegalArgumentException("lockout must be positive");
        }
        this.maxFailures = maxFailures;
        this.lockout = lockout;
    }

    /** key 当前是否处于锁定期。 */
    public boolean isLocked(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= maxFailures;
        }
    }

    /** 记录一次失败。 */
    public void recordFailure(String key) {
        Instant now = Instant.now();
        failures.compute(key, (ignored, attempts) -> {
            Deque<Instant> deque = attempts == null ? new ArrayDeque<>() : attempts;
            synchronized (deque) {
                deque.addLast(now);
                prune(deque);
            }
            return deque;
        });
    }

    /** 认证成功后清除失败记录。 */
    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> attempts) {
        Instant cutoff = Instant.now().minus(lockout);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.pollFirst();
        }
    }
}
