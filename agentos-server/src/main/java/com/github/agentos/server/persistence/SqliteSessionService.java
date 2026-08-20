package com.github.agentos.server.persistence;

import com.github.agentos.kernel.Session;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.kernel.SessionState;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 SQLite 的会话服务。
 *
 * <p>会话状态以 JSON 快照保存在 {@code agent_sessions} 表；每次增量合并
 * 通过 UPSERT 原子完成（先读后写在同一连接事务中执行）。</p>
 */
public final class SqliteSessionService implements SessionService {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 创建 SQLite 会话服务并初始化表结构。 */
    public SqliteSessionService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public Session getOrCreate(String sessionId, String userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return select(sessionId).orElseGet(() -> insert(sessionId, userId));
    }

    @Override
    public Optional<Session> find(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return select(sessionId);
    }

    @Override
    public List<Session> recent(int limit) {
        return recent(0, limit);
    }

    @Override
    public List<Session> recent(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT session_id, user_id, state, created_at_ms, last_active_at_ms "
                                + "FROM agent_sessions ORDER BY last_active_at_ms DESC "
                                + "LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<Session> sessions = new java.util.ArrayList<>();
                while (result.next()) {
                    sessions.add(mapRow(result));
                }
                return List.copyOf(sessions);
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("listing recent sessions", exception);
        }
    }

    @Override
    public long count() {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_sessions");
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException exception) {
            throw SqliteSupport.failure("counting sessions", exception);
        }
    }

    @Override
    public boolean delete(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM agent_sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw SqliteSupport.failure("deleting session " + sessionId, exception);
        }
    }

    @Override
    public Session applyDelta(String sessionId, Map<String, Object> delta) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection connection = SqliteSupport.open(dataSource)) {
            connection.setAutoCommit(false);
            try {
                Session current = selectOn(connection, sessionId)
                        .orElseGet(() -> Session.create(sessionId, "unknown-user"));
                Session updated = current
                        .withState(current.state().withDelta(delta))
                        .touch(Instant.now());
                upsertOn(connection, updated);
                connection.commit();
                return updated;
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("applying session delta for " + sessionId, exception);
        }
    }

    private Optional<Session> select(String sessionId) {
        try (Connection connection = SqliteSupport.open(dataSource)) {
            return selectOn(connection, sessionId);
        } catch (SQLException exception) {
            throw SqliteSupport.failure("querying session " + sessionId, exception);
        }
    }

    private Optional<Session> selectOn(Connection connection, String sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT session_id, user_id, state, created_at_ms, last_active_at_ms "
                        + "FROM agent_sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapRow(result)) : Optional.empty();
            }
        }
    }

    private Session insert(String sessionId, String userId) {
        Session created = Session.create(sessionId, userId);
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO agent_sessions "
                                + "(session_id, user_id, state, created_at_ms, last_active_at_ms) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, created.sessionId());
            statement.setString(2, created.userId());
            statement.setString(3, objectMapper.writeValueAsString(created.state().asMap()));
            statement.setLong(4, created.createdAt().toEpochMilli());
            statement.setLong(5, created.lastActiveAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw SqliteSupport.failure("creating session " + sessionId, exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while encoding session state: "
                            + exception.getMessage(), exception);
        }
        // 并发创建时以库中既有记录为准。
        return select(sessionId).orElse(created);
    }

    private void upsertOn(Connection connection, Session session) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO agent_sessions "
                        + "(session_id, user_id, state, created_at_ms, last_active_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET "
                        + "user_id = excluded.user_id, state = excluded.state, "
                        + "last_active_at_ms = excluded.last_active_at_ms")) {
            statement.setString(1, session.sessionId());
            statement.setString(2, session.userId());
            statement.setString(3, objectMapper.writeValueAsString(session.state().asMap()));
            statement.setLong(4, session.createdAt().toEpochMilli());
            statement.setLong(5, session.lastActiveAt().toEpochMilli());
            statement.executeUpdate();
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while encoding session state: "
                            + exception.getMessage(), exception);
        }
    }

    private Session mapRow(ResultSet result) throws SQLException {
        Map<String, Object> stateMap;
        try {
            stateMap = objectMapper.readValue(
                    result.getString(3), new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while decoding session state: "
                            + exception.getMessage(), exception);
        }
        return new Session(
                result.getString(1),
                result.getString(2),
                Instant.ofEpochMilli(result.getLong(4)),
                Instant.ofEpochMilli(result.getLong(5)),
                SessionState.of(stateMap));
    }
}
