package com.github.agentos.kernel;

import java.util.Objects;

/** 将发布的每条领域事件追加到 EventStore 的适配器。 */
public final class StoringAgentEventPublisher implements AgentEventPublisher {

    private final AgentEventStore store;

    /** 创建存储适配器。 */
    public StoringAgentEventPublisher(AgentEventStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** 追加事件。 */
    @Override
    public void publish(AgentEvent event) {
        store.append(event);
    }
}
