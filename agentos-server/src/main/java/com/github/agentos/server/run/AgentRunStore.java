package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentStreamEvent;

import java.util.List;
import java.util.Optional;

/** Run 快照与关键用户可见事件的持久化边界。 */
public interface AgentRunStore {
    void save(AgentRunSnapshot snapshot);
    Optional<AgentRunSnapshot> find(String runId);
    List<AgentRunSnapshot> listByUser(String userId);
    void append(AgentStreamEvent event);
    List<AgentStreamEvent> eventsAfter(String runId, long afterSeq);
    List<AgentStreamEvent> eventsBySession(String sessionId);
}
