package com.github.agentos.server.model;

/**
 * 非管理员调用默认模型被限频时抛出的领域异常。
 *
 * <p>由 {@link RoutingModelClients} 在调用模型前触发；建议在 HTTP 层
 * 转 429 Too Many Requests 并附带 {@code Retry-After} 头。</p>
 */
public final class NonAdminCallRateLimitException extends RuntimeException {

    private final long retryAfterMillis;

    public NonAdminCallRateLimitException(String reason, long retryAfterMillis) {
        super(reason == null ? "" : reason);
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}