package com.github.agentos.server.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite 存储的公共 JDBC 辅助：统一连接参数与异常转换。 */
final class SqliteSupport {

    /** 并发写入时的等待上限，超限报 SQLITE_BUSY。 */
    private static final String BUSY_TIMEOUT_PRAGMA = "PRAGMA busy_timeout=5000";

    private SqliteSupport() {
    }

    /** 打开连接并应用统一的 PRAGMA 设置。 */
    static Connection open(DataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute(BUSY_TIMEOUT_PRAGMA);
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    /** 把 SQL 异常转换为运行时异常并保留动作上下文。 */
    static RuntimeException failure(String action, SQLException exception) {
        return new IllegalStateException(
                "sqlite persistence failed while " + action + ": " + exception.getMessage(),
                exception);
    }

    /** 初始化全部运行态表结构；幂等可重复执行。 */
    static void initializeSchema(DataSource dataSource) {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS agent_events (
                    event_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    invocation_id TEXT NOT NULL,
                    agent_id TEXT NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    message TEXT NOT NULL,
                    data TEXT NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_agent_events_session "
                        + "ON agent_events(session_id, timestamp_ms)",
                "CREATE INDEX IF NOT EXISTS idx_agent_events_invocation "
                        + "ON agent_events(invocation_id, timestamp_ms)",
                """
                CREATE TABLE IF NOT EXISTS agent_checkpoints (
                    invocation_id TEXT PRIMARY KEY,
                    payload TEXT NOT NULL,
                    saved_at_ms INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_continuations (
                    invocation_id TEXT PRIMARY KEY,
                    payload TEXT NOT NULL,
                    saved_at_ms INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS session_usage (
                    session_id TEXT PRIMARY KEY,
                    model_calls INTEGER NOT NULL DEFAULT 0,
                    prompt_tokens INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0
                )
                """
        };
        try (Connection connection = open(dataSource);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            for (String sql : ddl) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw failure("initializing schema", exception);
        }
    }
}
