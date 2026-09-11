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
                    data TEXT NOT NULL,
                    actions TEXT NOT NULL DEFAULT '{}'
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_agent_events_session "
                        + "ON agent_events(session_id, timestamp_ms)",
                "CREATE INDEX IF NOT EXISTS idx_agent_events_invocation "
                        + "ON agent_events(invocation_id, timestamp_ms)",
                """
                CREATE TABLE IF NOT EXISTS agent_runs (
                    -- 一次完整 Agent 执行的唯一标识；SQLite 无法持久化字段注释。
                    run_id TEXT PRIMARY KEY,
                    -- Run 所属持续对话会话标识；SQLite 无法持久化字段注释。
                    session_id TEXT NOT NULL,
                    -- 创建 Run 的账户标识，用于数据隔离；SQLite 无法持久化字段注释。
                    user_id TEXT NOT NULL,
                    -- Run 状态机枚举值；SQLite 无法持久化字段注释。
                    status TEXT NOT NULL,
                    -- Run 创建时间（Unix 毫秒）；SQLite 无法持久化字段注释。
                    created_at_ms INTEGER NOT NULL,
                    -- Run 最近更新时间（Unix 毫秒）；SQLite 无法持久化字段注释。
                    updated_at_ms INTEGER NOT NULL,
                    -- Run 最终事实快照 JSON；SQLite 无法持久化字段注释。
                    snapshot_payload TEXT NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_agent_runs_owner_created "
                        + "ON agent_runs(user_id, created_at_ms DESC)",
                "CREATE INDEX IF NOT EXISTS idx_agent_runs_session_created "
                        + "ON agent_runs(session_id, created_at_ms)",
                """
                CREATE TABLE IF NOT EXISTS agent_stream_events (
                    -- 全局唯一事件标识；SQLite 无法持久化字段注释。
                    event_id TEXT PRIMARY KEY,
                    -- Agent 流协议版本号；SQLite 无法持久化字段注释。
                    schema_version TEXT NOT NULL,
                    -- 稳定的点分事件名；SQLite 无法持久化字段注释。
                    event_name TEXT NOT NULL,
                    -- 事件所属 Run 标识；SQLite 无法持久化字段注释。
                    run_id TEXT NOT NULL,
                    -- 事件所属对话轮次标识；SQLite 无法持久化字段注释。
                    turn_id TEXT NOT NULL,
                    -- 事件所属会话标识；SQLite 无法持久化字段注释。
                    session_id TEXT NOT NULL,
                    -- 消息、工具或产物对象标识；SQLite 无法持久化字段注释。
                    item_id TEXT NOT NULL,
                    -- 产生事件的 Agent 标识；SQLite 无法持久化字段注释。
                    agent_id TEXT NOT NULL,
                    -- 父 Run 标识，根 Run 为空；SQLite 无法持久化字段注释。
                    parent_run_id TEXT NOT NULL DEFAULT '',
                    -- 单个 Run 内严格递增序号；SQLite 无法持久化字段注释。
                    event_seq INTEGER NOT NULL,
                    -- 事件发生时间（Unix 毫秒）；SQLite 无法持久化字段注释。
                    occurred_at_ms INTEGER NOT NULL,
                    -- USER 或 INTERNAL 可见性；SQLite 无法持久化字段注释。
                    visibility TEXT NOT NULL,
                    -- 有界结构化事件数据 JSON；SQLite 无法持久化字段注释。
                    event_data TEXT NOT NULL,
                    UNIQUE(run_id, event_seq)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_agent_stream_events_run_seq "
                        + "ON agent_stream_events(run_id, event_seq)",
                "CREATE INDEX IF NOT EXISTS idx_agent_stream_events_session_time "
                        + "ON agent_stream_events(session_id, occurred_at_ms, event_seq)",
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
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_sessions (
                    session_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    last_active_at_ms INTEGER NOT NULL,
                    -- 会话软删除时间（Unix 毫秒），空值表示未删除。
                    deleted_at_ms INTEGER
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS users (
                    username TEXT PRIMARY KEY,
                    password_hash TEXT NOT NULL,
                    roles TEXT NOT NULL DEFAULT '',
                    created_at_ms INTEGER NOT NULL
                )
                """
        };
        try (Connection connection = open(dataSource);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            for (String sql : ddl) {
                statement.execute(sql);
            }
            upgradeLegacyEventTable(statement);
            upgradeLegacySessionTable(statement);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_agent_sessions_owner_active "
                    + "ON agent_sessions(user_id, deleted_at_ms, last_active_at_ms DESC)");
        } catch (SQLException exception) {
            throw failure("initializing schema", exception);
        }
    }

    /** 为旧库的 agent_events 补充 actions 列；列已存在时忽略报错。 */
    private static void upgradeLegacyEventTable(Statement statement) {
        try {
            statement.execute("ALTER TABLE agent_events ADD COLUMN actions TEXT NOT NULL DEFAULT '{}'");
        } catch (SQLException ignored) {
            // 列已存在或表为新建结构，无需升级。
        }
    }

    /** 为旧库的 agent_sessions 补充软删除时间列。 */
    private static void upgradeLegacySessionTable(Statement statement) {
        try {
            statement.execute("""
                    -- 会话软删除时间（Unix 毫秒）；SQLite 无法持久化字段注释。
                    ALTER TABLE agent_sessions ADD COLUMN deleted_at_ms INTEGER
                    """);
        } catch (SQLException ignored) {
            // 列已存在或表为新建结构，无需升级。
        }
    }
}
