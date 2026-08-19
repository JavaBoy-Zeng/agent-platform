package com.github.agentos.server.persistence;

import com.github.agentos.server.usage.UsageStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 基于 SQLite 的会话用量存储。
 *
 * <p>累加语义通过 UPSERT 在数据库端原子完成，写入失败只记日志：
 * 用量属于可丢失的观测数据，不应影响模型调用链路。</p>
 */
public final class SqliteUsageStore implements UsageStore {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SqliteUsageStore.class);

    private final DataSource dataSource;

    /** 创建 SQLite 用量存储并初始化表结构。 */
    public SqliteUsageStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public void increment(String sessionId, long promptTokens, long completionTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO session_usage "
                                + "(session_id, model_calls, prompt_tokens, completion_tokens) "
                                + "VALUES (?, 1, ?, ?) "
                                + "ON CONFLICT(session_id) DO UPDATE SET "
                                + "model_calls = model_calls + 1, "
                                + "prompt_tokens = prompt_tokens + excluded.prompt_tokens, "
                                + "completion_tokens = completion_tokens + excluded.completion_tokens")) {
            statement.setString(1, sessionId);
            statement.setLong(2, Math.max(0, promptTokens));
            statement.setLong(3, Math.max(0, completionTokens));
            statement.executeUpdate();
        } catch (SQLException exception) {
            LOGGER.warn("[usage-store] increment failed sessionId={} error={}",
                    sessionId, exception.getMessage());
        }
    }

    @Override
    public SessionUsage load(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT model_calls, prompt_tokens, completion_tokens "
                                + "FROM session_usage WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new SessionUsage(
                                result.getLong(1), result.getLong(2), result.getLong(3))
                        : SessionUsage.zero();
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("loading usage " + sessionId, exception);
        }
    }
}
