package com.github.agentos.server.usage;

import com.github.agentos.planner.ModelUsage;
import com.github.agentos.planner.ModelUsageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话累计模型 token 用量的记账器。
 *
 * <p>同时作为 {@link ModelUsageListener} 接入两个模型客户端，
 * 规划路径与直答路径的用量都会汇入同一会话账本，用于量化
 * 意图分级等优化的实际收益。</p>
 */
@Component
public final class UsageRecorder implements ModelUsageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsageRecorder.class);

    private final Map<String, SessionUsage> sessions = new ConcurrentHashMap<>();

    /** 记录一次调用并累计到会话账本。 */
    @Override
    public void onUsage(String sessionId, ModelUsage usage) {
        if (sessionId == null || sessionId.isBlank() || usage == null) {
            return;
        }
        SessionUsage updated = sessions.compute(sessionId, (key, current) -> {
            SessionUsage base = current == null
                    ? new SessionUsage(0, 0, 0) : current;
            return new SessionUsage(
                    base.modelCalls() + 1,
                    base.promptTokens() + usage.promptTokens(),
                    base.completionTokens() + usage.completionTokens());
        });
        LOGGER.info("[usage] sessionId={} model={} promptTokens={} completionTokens={} "
                        + "sessionTotal={}",
                sessionId, usage.model(), usage.promptTokens(),
                usage.completionTokens(), updated.totalTokens());
    }

    /** 查询会话累计用量；未知会话返回零值。 */
    public SessionUsage summary(String sessionId) {
        return sessions.getOrDefault(sessionId, new SessionUsage(0, 0, 0));
    }

    /** 一个会话的累计用量。 */
    public record SessionUsage(long modelCalls, long promptTokens, long completionTokens) {
        /** 输入输出合计。 */
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
