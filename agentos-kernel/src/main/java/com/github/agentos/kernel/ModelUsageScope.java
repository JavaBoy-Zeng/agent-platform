package com.github.agentos.kernel;

/**
 * 同步模型调用的内部计账作用域。执行会话保持独立，用量归根任务；
 * 作用域由运行时建立，不能通过用户请求属性指定其他会话的账本。
 */
public final class ModelUsageScope implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;

    private ModelUsageScope(String sessionId) {
        previous = CURRENT.get();
        CURRENT.set(sessionId);
    }

    public static ModelUsageScope open(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        return new ModelUsageScope(sessionId);
    }

    public static String sessionId(String fallback) {
        String scoped = CURRENT.get();
        return scoped == null ? fallback : scoped;
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
