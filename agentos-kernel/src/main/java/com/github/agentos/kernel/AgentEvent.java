package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;

/** Agent 执行轨迹中的一等领域事件。 */
public interface AgentEvent {
    String eventId();
    String sessionId();
    String invocationId();
    String agentId();
    Instant timestamp();
    AgentEventType type();
    String message();
    Map<String, Object> data();
}
