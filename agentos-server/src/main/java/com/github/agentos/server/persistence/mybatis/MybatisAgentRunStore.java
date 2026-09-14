package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.agentos.kernel.AgentStreamEvent;
import com.github.agentos.server.run.AgentRunSnapshot;
import com.github.agentos.server.run.AgentRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL 中的 Run 快照和关键流事件存储。 */
public final class MybatisAgentRunStore implements AgentRunStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MybatisAgentRunStore.class);
    private final AgentRunMapper runMapper;
    private final AgentStreamEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public MybatisAgentRunStore(
            AgentRunMapper runMapper,
            AgentStreamEventMapper eventMapper,
            ObjectMapper objectMapper) {
        this.runMapper = Objects.requireNonNull(runMapper);
        this.eventMapper = Objects.requireNonNull(eventMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void save(AgentRunSnapshot snapshot) {
        try {
            var row = new PersistenceRows.AgentRunRow(
                    snapshot.runId(), snapshot.sessionId(), snapshot.userId(),
                    snapshot.status().name(), snapshot.createdAt(), snapshot.updatedAt(),
                    objectMapper.writeValueAsString(snapshot));
            if (runMapper.updateById(row) == 0) runMapper.insert(row);
        } catch (RuntimeException exception) {
            throw persistenceFailure("save run " + snapshot.runId(), exception);
        }
    }

    @Override
    public Optional<AgentRunSnapshot> find(String runId) {
        PersistenceRows.AgentRunRow row = runMapper.selectById(runId);
        return row == null ? Optional.empty() : Optional.of(snapshot(row));
    }

    @Override
    public List<AgentRunSnapshot> listByUser(String userId) {
        return runMapper.selectList(new QueryWrapper<PersistenceRows.AgentRunRow>()
                        .eq("user_id", userId).orderByDesc("created_at"))
                .stream().map(this::snapshot).toList();
    }

    @Override
    public void append(AgentStreamEvent event) {
        if (!AgentStreamEvent.Type.fromWireName(event.event()).durable()) return;
        try {
            var row = new PersistenceRows.AgentStreamEventRow(
                    event.eventId(), event.schemaVersion(), event.event(), event.runId(),
                    event.turnId(), event.sessionId(), event.itemId(), event.agentId(),
                    event.parentRunId(), event.seq(), event.timestamp(), event.visibility().name(),
                    objectMapper.writeValueAsString(event.data()));
            if (eventMapper.updateById(row) == 0) eventMapper.insert(row);
        } catch (RuntimeException exception) {
            throw persistenceFailure("append stream event " + event.eventId(), exception);
        }
    }

    @Override
    public List<AgentStreamEvent> eventsAfter(String runId, long afterSeq) {
        return eventMapper.selectList(new QueryWrapper<PersistenceRows.AgentStreamEventRow>()
                        .eq("run_id", runId).gt("event_seq", afterSeq).orderByAsc("event_seq"))
                .stream().map(this::event).toList();
    }

    @Override
    public List<AgentStreamEvent> eventsBySession(String sessionId) {
        return eventMapper.selectList(new QueryWrapper<PersistenceRows.AgentStreamEventRow>()
                        .eq("session_id", sessionId).orderByAsc("occurred_at", "event_seq"))
                .stream().map(this::event).toList();
    }

    private AgentRunSnapshot snapshot(PersistenceRows.AgentRunRow row) {
        try {
            return objectMapper.readValue(row.snapshotPayload(), AgentRunSnapshot.class);
        } catch (JacksonException exception) {
            throw persistenceFailure("decode run " + row.runId(), exception);
        }
    }

    private AgentStreamEvent event(PersistenceRows.AgentStreamEventRow row) {
        try {
            Map<String, Object> data = objectMapper.readValue(
                    row.eventData(), new TypeReference<Map<String, Object>>() { });
            return new AgentStreamEvent(
                    row.schemaVersion(), row.eventName(), row.eventId(), row.runId(),
                    row.turnId(), row.sessionId(), row.itemId(), row.agentId(), row.parentRunId(),
                    row.eventSeq(), row.occurredAt(),
                    AgentStreamEvent.Visibility.valueOf(row.visibility()), data);
        } catch (JacksonException exception) {
            throw persistenceFailure("decode stream event " + row.eventId(), exception);
        }
    }

    private static RuntimeException persistenceFailure(String action, Throwable cause) {
        LOGGER.error("[agent-run-store] {} failed: {}", action, cause.getMessage());
        return new IllegalStateException("agent run persistence failed while " + action, cause);
    }
}
