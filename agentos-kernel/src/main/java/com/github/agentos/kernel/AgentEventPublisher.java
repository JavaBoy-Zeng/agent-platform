package com.github.agentos.kernel;

/** 发布 Agent 领域事件的核心协议，kernel 不依赖具体消息或 Spring 实现。 */
@FunctionalInterface
public interface AgentEventPublisher {
    /** 不处理事件的默认发布器。 */
    AgentEventPublisher NOOP = event -> { };

    /** 同步发布事件；实现应快速返回并自行隔离观察端故障。 */
    void publish(AgentEvent event);
}
