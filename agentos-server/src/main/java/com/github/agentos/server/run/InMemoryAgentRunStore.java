package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentStreamEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 测试和演示模式使用的进程内 Run Store。 */
public final class InMemoryAgentRunStore implements AgentRunStore {
    private final ConcurrentMap<String, AgentRunSnapshot> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AgentStreamEvent>> events = new ConcurrentHashMap<>();

    @Override
    public void save(AgentRunSnapshot snapshot) {
        runs.put(snapshot.runId(), snapshot);
    }

    @Override
    public Optional<AgentRunSnapshot> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<AgentRunSnapshot> listByUser(String userId) {
        return runs.values().stream()
                .filter(run -> userId.equals(run.userId()))
                .sorted(Comparator.comparing(AgentRunSnapshot::createdAt).reversed())
                .toList();
    }

    @Override
    public void append(AgentStreamEvent event) {
        events.computeIfAbsent(event.runId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(event);
    }

    @Override
    public List<AgentStreamEvent> eventsAfter(String runId, long afterSeq) {
        return copy(runId).stream().filter(event -> event.seq() > afterSeq)
                .sorted(Comparator.comparingLong(AgentStreamEvent::seq)).toList();
    }

    @Override
    public List<AgentStreamEvent> eventsBySession(String sessionId) {
        return events.values().stream().flatMap(list -> List.copyOf(list).stream())
                .filter(event -> event.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(AgentStreamEvent::timestamp)
                        .thenComparingLong(AgentStreamEvent::seq))
                .toList();
    }

    private List<AgentStreamEvent> copy(String runId) {
        List<AgentStreamEvent> found = events.get(runId);
        return found == null ? List.of() : List.copyOf(found);
    }
}
