package com.github.agentos.server.persistence;

import com.github.agentos.agent.loop.ContinuationStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 SQLite 的主 Agent 续跑状态存储。
 *
 * <p>整条 {@link PersistedContinuation} 序列化为 JSON 载荷；进程重启后
 * 审批恢复可读回剩余计划与累计结果。写入失败抛出异常，由 PlanExecuteAgent 决定降级策略。</p>
 */
public final class SqliteContinuationStore implements ContinuationStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 创建 SQLite 续跑状态存储并初始化表结构。 */
    public SqliteContinuationStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public void save(String invocationId, PersistedContinuation continuation) {
        requireInvocationId(invocationId);
        Objects.requireNonNull(continuation, "continuation must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR REPLACE INTO agent_continuations "
                                + "(invocation_id, payload, saved_at_ms) VALUES (?, ?, ?)")) {
            statement.setString(1, invocationId);
            statement.setString(2, objectMapper.writeValueAsString(continuation));
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException | JacksonException exception) {
            throw SqliteSupport.failure(
                    "saving continuation " + invocationId,
                    exception instanceof SQLException sql ? sql
                            : new SQLException(exception.getMessage(), exception));
        }
    }

    @Override
    public Optional<PersistedContinuation> load(String invocationId) {
        requireInvocationId(invocationId);
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload FROM agent_continuations WHERE invocation_id = ?")) {
            statement.setString(1, invocationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(objectMapper.readValue(
                        result.getString(1), PersistedContinuation.class));
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("loading continuation " + invocationId, exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while decoding continuation " + invocationId
                            + ": " + exception.getMessage(), exception);
        }
    }

    @Override
    public void delete(String invocationId) {
        requireInvocationId(invocationId);
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM agent_continuations WHERE invocation_id = ?")) {
            statement.setString(1, invocationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw SqliteSupport.failure("deleting continuation " + invocationId, exception);
        }
    }

    private static void requireInvocationId(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
    }
}
