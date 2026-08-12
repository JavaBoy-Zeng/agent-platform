package com.github.agentos.kernel;

import java.util.List;
import java.util.Objects;

/** 将同一领域事件依次发送到多个发布目标的组合发布器。 */
public final class CompositeAgentEventPublisher implements AgentEventPublisher {

    private final List<AgentEventPublisher> delegates;

    /** 创建并复制发布目标列表。 */
    public CompositeAgentEventPublisher(List<AgentEventPublisher> delegates) {
        this.delegates = List.copyOf(
                Objects.requireNonNull(delegates, "delegates must not be null"));
    }

    /** 依次发布并隔离单个目标的异常。 */
    @Override
    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        for (AgentEventPublisher delegate : delegates) {
            try {
                delegate.publish(event);
            } catch (RuntimeException ignored) {
                // 单个观察目标故障不影响其他发布目标。
            }
        }
    }
}
