package com.github.agentos.kernel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 使用并发内存集合保存 Agent 事件的默认 EventStore。 */
public final class InMemoryAgentEventStore implements AgentEventStore {

    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentEvent>> byInvocation =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentEvent>> bySession =
            new ConcurrentHashMap<>();

    /** 同时写入 Invocation 和 Session 两个查询索引。 */
    @Override
    public void append(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        byInvocation.computeIfAbsent(
                event.invocationId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
        bySession.computeIfAbsent(
                event.sessionId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    /** 返回 Invocation 轨迹的不可变副本。 */
    @Override
    public List<AgentEvent> findByInvocationId(String invocationId) {
        requireText(invocationId, "invocationId");
        return List.copyOf(byInvocation.getOrDefault(
                invocationId, new CopyOnWriteArrayList<>()));
    }

    /** 返回 Session 轨迹的不可变副本。 */
    @Override
    public List<AgentEvent> findBySessionId(String sessionId) {
        requireText(sessionId, "sessionId");
        return List.copyOf(bySession.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
