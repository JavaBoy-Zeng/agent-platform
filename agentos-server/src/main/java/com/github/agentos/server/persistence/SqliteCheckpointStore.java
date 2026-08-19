package com.github.agentos.server.persistence;

import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.CheckpointStore;
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
 * 基于 SQLite 的 Invocation Checkpoint 存储。
 *
 * <p>Checkpoint 以整条 JSON 载荷保存，字段演进只需保持记录结构可序列化。
 * 写入失败抛出运行时异常：checkpoint 丢失会直接破坏审批恢复语义，必须尽早暴露。</p>
 */
public final class SqliteCheckpointStore implements CheckpointStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 创建 SQLite Checkpoint 存储并初始化表结构。 */
    public SqliteCheckpointStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR REPLACE INTO agent_checkpoints "
                                + "(invocation_id, payload, saved_at_ms) VALUES (?, ?, ?)")) {
            statement.setString(1, checkpoint.invocationId());
            statement.setString(2, objectMapper.writeValueAsString(checkpoint));
            statement.setLong(3, checkpoint.savedAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException | JacksonException exception) {
            throw SqliteSupport.failure(
                    "saving checkpoint " + checkpoint.invocationId(),
                    exception instanceof SQLException sql ? sql
                            : new SQLException(exception.getMessage(), exception));
        }
    }

    @Override
    public Optional<AgentCheckpoint> load(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload FROM agent_checkpoints WHERE invocation_id = ?")) {
            statement.setString(1, invocationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(objectMapper.readValue(
                        result.getString(1), AgentCheckpoint.class));
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("loading checkpoint " + invocationId, exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "sqlite persistence failed while decoding checkpoint " + invocationId
                            + ": " + exception.getMessage(), exception);
        }
    }

    @Override
    public void delete(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM agent_checkpoints WHERE invocation_id = ?")) {
            statement.setString(1, invocationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw SqliteSupport.failure("deleting checkpoint " + invocationId, exception);
        }
    }
}
