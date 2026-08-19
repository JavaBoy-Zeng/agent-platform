package com.github.agentos.server.persistence;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.EventActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 SQLite 的领域事件存储。
 *
 * <p>写入失败只记录日志不抛出：事件属于观察数据，不能因持久化问题中断 Agent 运行。
 * 查询按（时间戳、rowid）排序，保证同毫秒事件保持写入顺序。</p>
 */
public final class SqliteAgentEventStore implements AgentEventStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteAgentEventStore.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 创建 SQLite 事件存储并初始化表结构。 */
    public SqliteAgentEventStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public void append(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR REPLACE INTO agent_events "
                                + "(event_id, session_id, invocation_id, agent_id, timestamp_ms, "
                                + "type, message, data, actions) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.sessionId());
            statement.setString(3, event.invocationId());
            statement.setString(4, event.agentId());
            statement.setLong(5, event.timestamp().toEpochMilli());
            statement.setString(6, event.type().name());
            statement.setString(7, event.message());
            statement.setString(8, objectMapper.writeValueAsString(event.data()));
            statement.setString(9, objectMapper.writeValueAsString(event.actions()));
            statement.executeUpdate();
        } catch (SQLException | JacksonException exception) {
            LOGGER.warn("[event-store] append failed type={} eventId={} error={}",
                    event.type(), event.eventId(), exception.getMessage());
        }
    }

    @Override
    public List<AgentEvent> findByInvocationId(String invocationId) {
        return query("invocation_id", invocationId);
    }

    @Override
    public List<AgentEvent> findBySessionId(String sessionId) {
        return query("session_id", sessionId);
    }

    private List<AgentEvent> query(String column, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " must not be blank");
        }
        String sql = "SELECT event_id, session_id, invocation_id, agent_id, timestamp_ms, "
                + "type, message, data, actions FROM agent_events WHERE " + column
                + " = ? ORDER BY timestamp_ms, rowid";
        List<AgentEvent> events = new ArrayList<>();
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new DefaultAgentEvent(
                            result.getString(1),
                            result.getString(2),
                            result.getString(3),
                            result.getString(4),
                            Instant.ofEpochMilli(result.getLong(5)),
                            AgentEventType.valueOf(result.getString(6)),
                            result.getString(7),
                            objectMapper.readValue(
                                    result.getString(8),
                                    new TypeReference<Map<String, Object>>() { }),
                            readActions(result.getString(9))));
                }
            }
            return List.copyOf(events);
        } catch (SQLException exception) {
            throw SqliteSupport.failure("querying events by " + column, exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while decoding event data: "
                            + exception.getMessage(), exception);
        }
    }

    private EventActions readActions(String json) throws JacksonException {
        if (json == null || json.isBlank()) {
            return EventActions.NONE;
        }
        return objectMapper.readValue(json, EventActions.class);
    }
}
