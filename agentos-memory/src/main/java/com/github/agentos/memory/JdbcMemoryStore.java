package com.github.agentos.memory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 JDBC 和版本化 SQL 迁移的 SQLite {@link MemoryStore} 实现。
 *
 * <p>所有写入都在事务中完成；L0 使用主键幂等插入，L1-L3 使用版本条件更新，查询严格
 * 复现 {@link MemoryScope} 的会话、参与者和任务边界。传入的 {@link DataSource} 必须连接
 * SQLite 数据库。</p>
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "/com/github/agentos/memory/migration/V1__memory_core.sql"),
            new Migration(2, "/com/github/agentos/memory/migration/V2__memory_indexes.sql"));

    private final DataSource dataSource;

    /**
     * 创建存储并自动执行尚未应用的数据库迁移。
     *
     * @param dataSource 指向 SQLite 数据库的连接数据源
     */
    public JdbcMemoryStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        migrate();
    }

    /**
     * 返回当前已应用的最高 schema 版本。
     *
     * @return schema 版本；尚无业务迁移时为 0
     */
    public int schemaVersion() {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM memory_schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException exception) {
            throw failure("read schema version", exception);
        }
    }

    @Override
    public void saveTurn(CompletedTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        transaction(connection -> {
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO memory_turns (
                        id, team_id, user_id, agent_id, session_id, task_id,
                        user_input, assistant_output, completed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO NOTHING
                    """)) {
                statement.setString(1, turn.id());
                setScope(statement, 2, turn.scope());
                statement.setString(7, turn.userInput());
                statement.setString(8, turn.assistantOutput());
                statement.setLong(9, epochMillis(turn.completedAt()));
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) return null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO memory_turn_tool_outputs (turn_id, output_index, content)
                    VALUES (?, ?, ?)
                    """)) {
                for (int index = 0; index < turn.toolOutputs().size(); index++) {
                    statement.setString(1, turn.id());
                    statement.setInt(2, index);
                    statement.setString(3, turn.toolOutputs().get(index));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    @Override
    public Optional<CompletedTurn> findTurn(String turnId) {
        if (turnId == null || turnId.isBlank()) return Optional.empty();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM memory_turns WHERE id = ?")) {
            statement.setString(1, turnId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTurn(connection, result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("find completed turn", exception);
        }
    }

    @Override
    public List<CompletedTurn> listRecentTurns(MemoryScope scope, int limit) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit <= 0) return List.of();
        String sql = """
                SELECT * FROM (
                    SELECT * FROM memory_turns
                    WHERE team_id = ? AND user_id = ? AND agent_id = ? AND session_id = ?
                      AND (? = '' OR task_id = '' OR task_id = ?)
                    ORDER BY completed_at DESC, id DESC
                    LIMIT ?
                ) recent
                ORDER BY completed_at ASC, id ASC
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.teamId());
            statement.setString(2, scope.userId());
            statement.setString(3, scope.agentId());
            statement.setString(4, scope.sessionId());
            statement.setString(5, scope.taskId());
            statement.setString(6, scope.taskId());
            statement.setInt(7, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CompletedTurn> turns = new ArrayList<>();
                while (result.next()) turns.add(readTurn(connection, result));
                return List.copyOf(turns);
            }
        } catch (SQLException exception) {
            throw failure("list recent turns", exception);
        }
    }

    @Override
    public void upsertAtomic(AtomicMemory memory) {
        Objects.requireNonNull(memory, "memory must not be null");
        String sql = """
                INSERT INTO memory_atomic (
                    id, team_id, user_id, agent_id, session_id, task_id, memory_type,
                    content, confidence, priority, version, source_turn_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    team_id = excluded.team_id,
                    user_id = excluded.user_id,
                    agent_id = excluded.agent_id,
                    session_id = excluded.session_id,
                    task_id = excluded.task_id,
                    memory_type = excluded.memory_type,
                    content = excluded.content,
                    confidence = excluded.confidence,
                    priority = excluded.priority,
                    version = excluded.version,
                    source_turn_id = excluded.source_turn_id,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                WHERE excluded.version >= memory_atomic.version
                """;
        update(sql, statement -> {
            statement.setString(1, memory.id());
            setScope(statement, 2, memory.scope());
            statement.setString(7, memory.type().name());
            statement.setString(8, memory.content());
            statement.setDouble(9, memory.confidence());
            statement.setInt(10, memory.priority());
            statement.setInt(11, memory.version());
            statement.setString(12, memory.sourceTurnId());
            statement.setLong(13, epochMillis(memory.createdAt()));
            statement.setLong(14, epochMillis(memory.updatedAt()));
        }, "upsert atomic memory");
    }

    @Override
    public List<AtomicMemory> listAtomic(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        String sql = """
                SELECT * FROM memory_atomic
                WHERE team_id = ? AND user_id = ? AND agent_id = ?
                  AND (? = '' OR task_id = '' OR task_id = ?)
                ORDER BY updated_at DESC, id ASC
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setActorAndTask(statement, scope);
            try (ResultSet result = statement.executeQuery()) {
                List<AtomicMemory> memories = new ArrayList<>();
                while (result.next()) memories.add(readAtomic(result));
                return List.copyOf(memories);
            }
        } catch (SQLException exception) {
            throw failure("list atomic memories", exception);
        }
    }

    @Override
    public void saveScenario(ScenarioMemory scenario) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        String sql = """
                INSERT INTO memory_scenarios (
                    id, team_id, user_id, agent_id, session_id, task_id,
                    name, content, version, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    team_id = excluded.team_id,
                    user_id = excluded.user_id,
                    agent_id = excluded.agent_id,
                    session_id = excluded.session_id,
                    task_id = excluded.task_id,
                    name = excluded.name,
                    content = excluded.content,
                    version = excluded.version,
                    updated_at = excluded.updated_at
                WHERE excluded.version >= memory_scenarios.version
                """;
        update(sql, statement -> {
            statement.setString(1, scenario.id());
            setScope(statement, 2, scenario.scope());
            statement.setString(7, scenario.name());
            statement.setString(8, scenario.content());
            statement.setInt(9, scenario.version());
            statement.setLong(10, epochMillis(scenario.updatedAt()));
        }, "save scenario memory");
    }

    @Override
    public List<ScenarioMemory> listScenarios(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        String sql = """
                SELECT * FROM memory_scenarios
                WHERE team_id = ? AND user_id = ? AND agent_id = ?
                  AND (? = '' OR task_id = '' OR task_id = ?)
                ORDER BY updated_at DESC, id ASC
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setActorAndTask(statement, scope);
            try (ResultSet result = statement.executeQuery()) {
                List<ScenarioMemory> scenarios = new ArrayList<>();
                while (result.next()) scenarios.add(readScenario(result));
                return List.copyOf(scenarios);
            }
        } catch (SQLException exception) {
            throw failure("list scenario memories", exception);
        }
    }

    @Override
    public void saveProfile(ProfileMemory profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        String sql = """
                INSERT INTO memory_profiles (
                    id, team_id, user_id, agent_id, session_id, task_id,
                    content, version, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(team_id, user_id, agent_id) DO UPDATE SET
                    id = excluded.id,
                    session_id = excluded.session_id,
                    task_id = excluded.task_id,
                    content = excluded.content,
                    version = excluded.version,
                    updated_at = excluded.updated_at
                WHERE excluded.version >= memory_profiles.version
                """;
        update(sql, statement -> {
            statement.setString(1, profile.id());
            setScope(statement, 2, profile.scope());
            statement.setString(7, profile.content());
            statement.setInt(8, profile.version());
            statement.setLong(9, epochMillis(profile.updatedAt()));
        }, "save profile memory");
    }

    @Override
    public Optional<ProfileMemory> findProfile(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        String sql = """
                SELECT * FROM memory_profiles
                WHERE team_id = ? AND user_id = ? AND agent_id = ?
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.teamId());
            statement.setString(2, scope.userId());
            statement.setString(3, scope.agentId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProfile(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("find profile memory", exception);
        }
    }

    @Override
    public void saveJob(PipelineJob job) {
        Objects.requireNonNull(job, "job must not be null");
        String sql = """
                INSERT INTO memory_pipeline_jobs (
                    id, turn_id, team_id, user_id, agent_id, session_id, task_id,
                    stage, status, attempts, error, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    turn_id = excluded.turn_id,
                    team_id = excluded.team_id,
                    user_id = excluded.user_id,
                    agent_id = excluded.agent_id,
                    session_id = excluded.session_id,
                    task_id = excluded.task_id,
                    stage = excluded.stage,
                    status = excluded.status,
                    attempts = excluded.attempts,
                    error = excluded.error,
                    updated_at = excluded.updated_at
                """;
        update(sql, statement -> {
            statement.setString(1, job.id());
            statement.setString(2, job.turnId());
            setScope(statement, 3, job.scope());
            statement.setString(8, job.stage().name());
            statement.setString(9, job.status().name());
            statement.setInt(10, job.attempts());
            statement.setString(11, job.error());
            statement.setLong(12, epochMillis(job.updatedAt()));
        }, "save pipeline job");
    }

    @Override
    public Optional<PipelineJob> findJob(String jobId) {
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM memory_pipeline_jobs WHERE id = ?")) {
            statement.setString(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJob(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("find pipeline job", exception);
        }
    }

    @Override
    public List<PipelineJob> listRecoverableJobs(int maxAttempts) {
        if (maxAttempts <= 0) return List.of();
        String sql = """
                SELECT * FROM memory_pipeline_jobs
                WHERE status <> 'COMPLETED' AND attempts < ?
                ORDER BY updated_at ASC, id ASC
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxAttempts);
            try (ResultSet result = statement.executeQuery()) {
                List<PipelineJob> jobs = new ArrayList<>();
                while (result.next()) {
                    PipelineJob job = readJob(result);
                    jobs.add(job.status() == PipelineJob.Status.RUNNING ? job.retry() : job);
                }
                return List.copyOf(jobs);
            }
        } catch (SQLException exception) {
            throw failure("list recoverable pipeline jobs", exception);
        }
    }

    private synchronized void migrate() {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                executeScript(connection, readResource(
                        "/com/github/agentos/memory/migration/V0__migration_history.sql"));
                for (Migration migration : MIGRATIONS) {
                    if (isApplied(connection, migration.version())) continue;
                    executeScript(connection, readResource(migration.resource()));
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO memory_schema_migrations (version, description, applied_at)
                            VALUES (?, ?, ?)
                            """)) {
                        statement.setInt(1, migration.version());
                        statement.setString(2, migration.resource());
                        statement.setLong(3, Instant.now().toEpochMilli());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("migrate memory database", exception);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("execute memory transaction", exception);
        }
    }

    private void update(String sql, StatementBinder binder, String action) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure(action, exception);
        }
    }

    private static CompletedTurn readTurn(Connection connection, ResultSet result) throws SQLException {
        String id = result.getString("id");
        List<String> outputs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT content FROM memory_turn_tool_outputs
                WHERE turn_id = ? ORDER BY output_index ASC
                """)) {
            statement.setString(1, id);
            try (ResultSet outputRows = statement.executeQuery()) {
                while (outputRows.next()) outputs.add(outputRows.getString(1));
            }
        }
        return new CompletedTurn(
                id, readScope(result), result.getString("user_input"), result.getString("assistant_output"),
                outputs, instant(result, "completed_at"));
    }

    private static AtomicMemory readAtomic(ResultSet result) throws SQLException {
        return new AtomicMemory(
                result.getString("id"), readScope(result), MemoryType.valueOf(result.getString("memory_type")),
                result.getString("content"), result.getDouble("confidence"), result.getInt("priority"),
                result.getInt("version"), result.getString("source_turn_id"),
                instant(result, "created_at"), instant(result, "updated_at"));
    }

    private static ScenarioMemory readScenario(ResultSet result) throws SQLException {
        return new ScenarioMemory(
                result.getString("id"), readScope(result), result.getString("name"),
                result.getString("content"), result.getInt("version"), instant(result, "updated_at"));
    }

    private static ProfileMemory readProfile(ResultSet result) throws SQLException {
        return new ProfileMemory(
                result.getString("id"), readScope(result), result.getString("content"),
                result.getInt("version"), instant(result, "updated_at"));
    }

    private static PipelineJob readJob(ResultSet result) throws SQLException {
        return new PipelineJob(
                result.getString("id"), result.getString("turn_id"), readScope(result),
                PipelineJob.Stage.valueOf(result.getString("stage")),
                PipelineJob.Status.valueOf(result.getString("status")),
                result.getInt("attempts"), result.getString("error"), instant(result, "updated_at"));
    }

    private static MemoryScope readScope(ResultSet result) throws SQLException {
        return new MemoryScope(
                result.getString("team_id"), result.getString("user_id"), result.getString("agent_id"),
                result.getString("session_id"), result.getString("task_id"));
    }

    private static void setScope(PreparedStatement statement, int firstIndex, MemoryScope scope)
            throws SQLException {
        statement.setString(firstIndex, scope.teamId());
        statement.setString(firstIndex + 1, scope.userId());
        statement.setString(firstIndex + 2, scope.agentId());
        statement.setString(firstIndex + 3, scope.sessionId());
        statement.setString(firstIndex + 4, scope.taskId());
    }

    private static void setActorAndTask(PreparedStatement statement, MemoryScope scope) throws SQLException {
        statement.setString(1, scope.teamId());
        statement.setString(2, scope.userId());
        statement.setString(3, scope.agentId());
        statement.setString(4, scope.taskId());
        statement.setString(5, scope.taskId());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    private static long epochMillis(Instant instant) {
        return Objects.requireNonNull(instant, "instant must not be null").toEpochMilli();
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM memory_schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        String withoutComments = script.replaceAll("(?m)^\\s*--.*$", "");
        for (String statementText : withoutComments.split(";")) {
            String sql = statementText.trim();
            if (sql.isEmpty()) continue;
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static String readResource(String resource) {
        try (InputStream stream = JdbcMemoryStore.class.getResourceAsStream(resource)) {
            if (stream == null) throw new MemoryAdapterException("missing database migration: " + resource);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().reduce("", (left, right) -> left + right + '\n');
            }
        } catch (IOException exception) {
            throw new MemoryAdapterException("failed to read database migration: " + resource, exception);
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static MemoryAdapterException failure(String action, SQLException exception) {
        return new MemoryAdapterException("failed to " + action + ": " + exception.getMessage(), exception);
    }

    private record Migration(int version, String resource) {
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
