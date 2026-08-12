package com.github.agentos.kernel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 支持运行期注册监听器的线程安全内存事件发布器。 */
public final class InMemoryAgentEventPublisher implements AgentEventPublisher {

    private final CopyOnWriteArrayList<Consumer<AgentEvent>> listeners;

    /** 创建没有初始监听器的发布器。 */
    public InMemoryAgentEventPublisher() {
        this(List.of());
    }

    /** 创建带初始监听器的发布器。 */
    public InMemoryAgentEventPublisher(List<Consumer<AgentEvent>> listeners) {
        this.listeners = new CopyOnWriteArrayList<>(
                Objects.requireNonNull(listeners, "listeners must not be null"));
    }

    /** 注册一个事件监听器。 */
    public void addListener(Consumer<AgentEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** 移除一个事件监听器。 */
    public void removeListener(Consumer<AgentEvent> listener) {
        listeners.remove(listener);
    }

    /** 向当前监听器发布事件，并隔离单个监听器故障。 */
    @Override
    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 观察端不得中断 Agent 主执行链路。
            }
        }
    }
}
