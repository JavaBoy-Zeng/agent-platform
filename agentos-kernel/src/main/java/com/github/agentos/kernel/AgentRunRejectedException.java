package com.github.agentos.kernel;

/** Runner 在进入执行链之前因并发保护拒绝请求。 */
public final class AgentRunRejectedException extends RuntimeException {

    /** 拒绝原因，供 HTTP 等适配层映射稳定状态码。 */
    public enum Reason {
        SESSION_BUSY,
        CAPACITY_EXCEEDED
    }

    private final Reason reason;

    public AgentRunRejectedException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
