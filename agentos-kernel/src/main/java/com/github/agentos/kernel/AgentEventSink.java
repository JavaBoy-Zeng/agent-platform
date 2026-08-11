package com.github.agentos.kernel;

/** Agent 运行事件的轻量消费协议。 */
@FunctionalInterface
public interface AgentEventSink {

    /** 不消费事件的默认实现。 */
    AgentEventSink NOOP = event -> { };

    /** 接收一条运行事件。实现不得长期阻塞 Agent 执行线程。 */
    void emit(AgentRunEvent event);
}
