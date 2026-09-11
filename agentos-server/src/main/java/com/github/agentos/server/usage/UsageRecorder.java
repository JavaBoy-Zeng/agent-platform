package com.github.agentos.server.usage;

import com.github.agentos.kernel.AgentPlugin;
import com.github.agentos.kernel.ModelUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按会话累计模型 token 用量的记账插件。
 *
 * <p>作为第一个 {@link AgentPlugin} 接入插件体系：模型客户端的用量回调经
 * {@code AgentPluginManager} 分发到这里，规划路径与直答路径的用量都汇入
 * 同一会话账本，用于量化意图分级等优化的实际收益。实际累计由可替换的
 * {@link UsageStore} 完成，内存或 SQLite 实现均可用。</p>
 */
@Component
public final class UsageRecorder implements AgentPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageStore store;

    /** 创建使用指定存储的记账器。 */
    public UsageRecorder(UsageStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public String name() {
        return "usage-recorder";
    }

    /** 记录一次调用并累计到会话账本。 */
    @Override
    public void onModelUsage(String sessionId, ModelUsage usage) {
        sessionId = com.github.agentos.kernel.ModelUsageScope.sessionId(sessionId);
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
