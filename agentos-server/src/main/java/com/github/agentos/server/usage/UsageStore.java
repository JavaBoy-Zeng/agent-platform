package com.github.agentos.server.usage;

import java.util.Objects;

/**
 * 会话模型用量的累计存储协议。
 *
 * <p>内存实现用于零依赖启动；SQLite 实现让账本跨进程重启保留。</p>
 */
public interface UsageStore {

    /** 累加一次模型调用的用量。 */
    void increment(String sessionId, long promptTokens, long completionTokens);

    /** 读取会话累计用量；未知会话返回零值。 */
    SessionUsage load(String sessionId);

    /** 一个会话的累计用量。 */
    record SessionUsage(long modelCalls, long promptTokens, long completionTokens) {

        /** 创建并校验。 */
        public SessionUsage {
            if (modelCalls < 0 || promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("usage counters must not be negative");
            }
        }

        /** 输入输出合计。 */
        public long totalTokens() {
            return promptTokens + completionTokens;
        }

        /** 零用量。 */
        public static SessionUsage zero() {
            return new SessionUsage(0, 0, 0);
        }
    }

    /** 进程内累计实现。 */
    final class InMemoryUsageStore implements UsageStore {

        private final java.util.concurrent.ConcurrentMap<String, SessionUsage> sessions =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void increment(String sessionId, long promptTokens, long completionTokens) {
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            sessions.merge(sessionId, new SessionUsage(1, promptTokens, completionTokens),
                    (current, delta) -> new SessionUsage(
                            current.modelCalls() + delta.modelCalls(),
                            current.promptTokens() + delta.promptTokens(),
                            current.completionTokens() + delta.completionTokens()));
        }

        @Override
        public SessionUsage load(String sessionId) {
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            return sessions.getOrDefault(sessionId, SessionUsage.zero());
        }
    }
}
