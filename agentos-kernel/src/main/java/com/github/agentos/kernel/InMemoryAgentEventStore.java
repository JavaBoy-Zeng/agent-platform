package com.github.agentos.kernel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用并发内存集合保存 Agent 事件的默认 EventStore。 */
public final class InMemoryAgentEventStore implements AgentEventStore {

    private static final int DEFAULT_MAX_INVOCATIONS = 10_000;
    private static final int DEFAULT_MAX_SESSIONS = 5_000;
    private static final int DEFAULT_MAX_EVENTS_PER_INVOCATION = 200;
    private static final int DEFAULT_MAX_EVENTS_PER_SESSION = 2_000;

    private final ConcurrentMap<String, EventBuffer> byInvocation =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EventBuffer> bySession =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> invocationOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> sessionOrder = new ConcurrentLinkedQueue<>();
    private final int maxInvocations;
    private final int maxSessions;
    private final int maxEventsPerInvocation;
    private final int maxEventsPerSession;

    /** 使用适合本地运行的默认有界容量。 */
    public InMemoryAgentEventStore() {
        this(DEFAULT_MAX_INVOCATIONS, DEFAULT_MAX_SESSIONS,
                DEFAULT_MAX_EVENTS_PER_INVOCATION, DEFAULT_MAX_EVENTS_PER_SESSION);
    }

    /** 创建显式指定索引和单键事件容量的内存事件存储。 */
    public InMemoryAgentEventStore(
            int maxInvocations,
            int maxSessions,
            int maxEventsPerInvocation,
            int maxEventsPerSession) {
        if (maxInvocations <= 0 || maxSessions <= 0
                || maxEventsPerInvocation <= 0 || maxEventsPerSession <= 0) {
            throw new IllegalArgumentException("event retention limits must be positive");
        }
        this.maxInvocations = maxInvocations;
        this.maxSessions = maxSessions;
        this.maxEventsPerInvocation = maxEventsPerInvocation;
        this.maxEventsPerSession = maxEventsPerSession;
    }

    /** 同时写入 Invocation 和 Session 两个查询索引。 */
    @Override
    public void append(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        buffer(
                byInvocation, invocationOrder, event.invocationId(),
                maxInvocations, maxEventsPerInvocation).add(event);
        buffer(
                bySession, sessionOrder, event.sessionId(),
                maxSessions, maxEventsPerSession).add(event);
    }

    /** 返回 Invocation 轨迹的不可变副本。 */
    @Override
    public List<AgentEvent> findByInvocationId(String invocationId) {
        requireText(invocationId, "invocationId");
        EventBuffer events = byInvocation.get(invocationId);
        return events == null ? List.of() : events.snapshot();
    }

    /** 返回 Session 轨迹的不可变副本。 */
    @Override
    public List<AgentEvent> findBySessionId(String sessionId) {
        requireText(sessionId, "sessionId");
        EventBuffer events = bySession.get(sessionId);
        return events == null ? List.of() : events.snapshot();
    }

    private static EventBuffer buffer(
            ConcurrentMap<String, EventBuffer> index,
            ConcurrentLinkedQueue<String> order,
            String key,
            int maxKeys,
            int maxEvents) {
        EventBuffer existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        EventBuffer created = new EventBuffer(maxEvents);
        EventBuffer raced = index.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        order.add(key);
        while (index.size() > maxKeys) {
            String expired = order.poll();
            if (expired == null) {
                break;
            }
            index.remove(expired);
        }
        return created;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static final class EventBuffer {
        private final ConcurrentLinkedDeque<AgentEvent> events = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger();
        private final int maxEvents;

        private EventBuffer(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        void add(AgentEvent event) {
            events.addLast(event);
            int currentSize = size.incrementAndGet();
            while (currentSize > maxEvents) {
                AgentEvent removed = events.pollFirst();
                if (removed == null) {
                    break;
                }
                currentSize = size.decrementAndGet();
            }
        }

        List<AgentEvent> snapshot() {
            return List.copyOf(events);
        }
    }
}
