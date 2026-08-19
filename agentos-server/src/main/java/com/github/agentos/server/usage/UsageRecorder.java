package com.github.agentos.server.usage;

import com.github.agentos.planner.ModelUsage;
import com.github.agentos.planner.ModelUsageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按会话累计模型 token 用量的记账器。
 *
 * <p>同时作为 {@link ModelUsageListener} 接入两个模型客户端，
 * 规划路径与直答路径的用量都汇入同一会话账本，用于量化
 * 意图分级等优化的实际收益。实际累计由可替换的 {@link UsageStore} 完成，
 * 内存或 SQLite 实现均可用。</p>
 */
@Component
public final class UsageRecorder implements ModelUsageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageStore store;

    /** 创建使用指定存储的记账器。 */
    public UsageRecorder(UsageStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store must not be null");
    }

    /** 记录一次调用并累计到会话账本。 */
    @Override
    public void onUsage(String sessionId, ModelUsage usage) {
        if (sessionId == null || sessionId.isBlank() || usage == null) {
            return;
        }
        store.increment(sessionId, usage.promptTokens(), usage.completionTokens());
        UsageStore.SessionUsage total = store.load(sessionId);
        LOGGER.info("[usage] sessionId={} model={} promptTokens={} completionTokens={} "
                        + "sessionTotal={}",
                sessionId, usage.model(), usage.promptTokens(),
                usage.completionTokens(), total.totalTokens());
    }

    /** 查询会话累计用量；未知会话返回零值。 */
    public UsageStore.SessionUsage summary(String sessionId) {
        return store.load(sessionId);
    }
}
