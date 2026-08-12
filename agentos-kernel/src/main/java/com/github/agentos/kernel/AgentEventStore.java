package com.github.agentos.kernel;

import java.util.List;

/** Agent 领域事件的追加与轨迹查询协议。 */
public interface AgentEventStore {
    /** 追加一条不可变领域事件。 */
    void append(AgentEvent event);

    /** 按 Invocation 标识返回时间顺序一致的完整轨迹。 */
    List<AgentEvent> findByInvocationId(String invocationId);

    /** 按 Session 标识返回该会话全部 Invocation 的事件轨迹。 */
    List<AgentEvent> findBySessionId(String sessionId);
}
