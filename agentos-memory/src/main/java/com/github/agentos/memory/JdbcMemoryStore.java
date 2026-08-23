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
import java.sql.Types;
import java.nio.ByteBuffer;
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
            new Migration(2, "/com/github/agentos/memory/migration/V2__memory_indexes.sql"),
            new Migration(3, "/com/github/agentos/memory/migration/V3__memory_lifecycle_vectors.sql"));

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
            insertTurn(connection, turn);
            return null;
        });
    }

    @Override
    public PipelineJob captureTurn(CompletedTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        return transaction(connection -> {
            insertTurn(connection, turn);
            CompletedTurn stored = findTurnByBusinessKey(connection, turn).orElseThrow();
            String jobId = "pipeline:" + stored.id();
            PipelineJob existing = findJob(connection, jobId).orElse(null);
            if (existing != null && existing.status() == PipelineJob.Status.COMPLETED) return existing;
            PipelineJob job = existing == null
                    ? PipelineJob.pending(stored.id(), stored.scope())
                    : existing.retry();
            upsertJob(connection, job);
            return job;
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
        transaction(connection -> {
            upsertAtomic(connection, memory);
            return null;
        });
    }

    @Override
    public Optional<AtomicMemory> findAtomic(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) return Optional.empty();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM memory_atomic WHERE id = ?")) {
            statement.setString(1, memoryId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAtomic(connection, result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("find atomic memory", exception);
        }
    }

    @Override
    public boolean deleteAtomic(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) return false;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM memory_atomic WHERE id = ?")) {
            statement.setString(1, memoryId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw failure("delete atomic memory", exception);
        }
    }

    @Override
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM memory_atomic WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            statement.setLong(1, epochMillis(now));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("purge expired atomic memories", exception);
        }
    }

    @Override
    public void supersedeAtomic(AtomicMemory previous, AtomicMemory replacement) {
        transaction(connection -> {
            AtomicMemory current = findAtomic(connection, previous.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "previous memory does not exist"));
            if (current.id().equals(replacement.id())) {
                throw new IllegalArgumentException("replacement must have a different id");
            }
            if (!current.scope().sameActor(replacement.scope())) {
                throw new IllegalArgumentException("replacement must belong to the same actor");
            }
            upsertAtomic(connection, replacement);
            upsertAtomic(connection, current.supersedeBy(
                    replacement.id(), replacement.sourceTurnId()));
            return null;
        });
    }

    @Override
    public List<AtomicMemory> listAtomic(MemoryScope scope) {
        return listAtomic(scope, false);
    }

    @Override
    public List<AtomicMemory> listAtomic(MemoryScope scope, boolean includeInactive) {
        Objects.requireNonNull(scope, "scope must not be null");
        String lifecycle = includeInactive ? "" : " AND status = 'ACTIVE' AND valid_from <= ?"
                + " AND (expires_at IS NULL OR expires_at > ?)";
        String sql = """
                SELECT * FROM memory_atomic
                WHERE team_id = ? AND user_id = ? AND agent_id = ?
                  AND (? = '' OR task_id = '' OR task_id = ?)
                """ + lifecycle + """
                ORDER BY updated_at DESC, id ASC
                """;
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setActorAndTask(statement, scope);
            if (!includeInactive) {
                long now = Instant.now().toEpochMilli();
                statement.setLong(6, now);
                statement.setLong(7, now);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<AtomicMemory> memories = new ArrayList<>();
                while (result.next()) memories.add(readAtomic(connection, result));
                return List.copyOf(memories);
            }
        } catch (SQLException exception) {
            throw failure("list atomic memories", exception);
        }
    }

    @Override
    public void saveVector(MemoryVector vector) {
        Objects.requireNonNull(vector, "vector must not be null");
        String sql = """
                INSERT INTO memory_embeddings (
                    memory_id, model, content_version, dimension, vector, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(memory_id, model) DO UPDATE SET
                    content_version = excluded.content_version,
                    dimension = excluded.dimension,
                    vector = excluded.vector,
                    updated_at = excluded.updated_at
                WHERE excluded.content_version >= memory_embeddings.content_version
                """;
        update(sql, statement -> {
            double[] values = vector.values();
            statement.setString(1, vector.memoryId());
            statement.setString(2, vector.model());
            statement.setInt(3, vector.contentVersion());
            statement.setInt(4, values.length);
            statement.setBytes(5, encodeVector(values));
            statement.setLong(6, epochMillis(vector.updatedAt()));
        }, "save memory vector");
    }

    @Override
    public Optional<MemoryVector> findVector(String memoryId, String model) {
        if (memoryId == null || memoryId.isBlank() || model == null || model.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT * FROM memory_embeddings WHERE memory_id = ? AND model = ?
                        """)) {
            statement.setString(1, memoryId);
            statement.setString(2, model);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                int dimension = result.getInt("dimension");
                return Optional.of(new MemoryVector(
                        memoryId, model, result.getInt("content_version"),
                        decodeVector(result.getBytes("vector"), dimension),
                        instant(result, "updated_at")));
            }
        } catch (SQLException exception) {
            throw failure("find memory vector", exception);
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

    private static void insertTurn(Connection connection, CompletedTurn turn) throws SQLException {
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_turns (
                    id, team_id, user_id, agent_id, session_id, task_id,
                    user_input, assistant_output, completed_at, business_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, turn.id());
            setScope(statement, 2, turn.scope());
            statement.setString(7, turn.userInput());
            statement.setString(8, turn.assistantOutput());
            statement.setLong(9, epochMillis(turn.completedAt()));
            statement.setString(10, turn.businessKey());
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) return;
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
    }

    private static void upsertAtomic(Connection connection, AtomicMemory memory) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_atomic (
                    id, team_id, user_id, agent_id, session_id, task_id, memory_type,
                    content, confidence, priority, version, source_turn_id, created_at, updated_at,
                    status, valid_from, expires_at, superseded_by_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    updated_at = excluded.updated_at,
                    status = excluded.status,
                    valid_from = excluded.valid_from,
                    expires_at = excluded.expires_at,
                    superseded_by_id = excluded.superseded_by_id
                WHERE excluded.version >= memory_atomic.version
                """)) {
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
            statement.setString(15, memory.status().name());
            statement.setLong(16, epochMillis(memory.validFrom()));
            if (memory.expiresAt() == null) statement.setNull(17, Types.BIGINT);
            else statement.setLong(17, epochMillis(memory.expiresAt()));
            statement.setString(18, memory.supersededById());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_atomic_sources (memory_id, source_id, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(memory_id, source_id) DO NOTHING
                """)) {
            for (String source : memory.sourceTurnIds()) {
                statement.setString(1, memory.id());
                statement.setString(2, source);
                statement.setLong(3, epochMillis(memory.updatedAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<PipelineJob> findJob(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM memory_pipeline_jobs WHERE id = ?")) {
            statement.setString(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readJob(result)) : Optional.empty();
            }
        }
    }

    private static Optional<AtomicMemory> findAtomic(
            Connection connection, String memoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM memory_atomic WHERE id = ?")) {
            statement.setString(1, memoryId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAtomic(connection, result)) : Optional.empty();
            }
        }
    }

    private static Optional<CompletedTurn> findTurnByBusinessKey(
            Connection connection, CompletedTurn candidate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM memory_turns
                WHERE team_id = ? AND user_id = ? AND agent_id = ? AND business_key = ?
                """)) {
            statement.setString(1, candidate.scope().teamId());
            statement.setString(2, candidate.scope().userId());
            statement.setString(3, candidate.scope().agentId());
            statement.setString(4, candidate.businessKey());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return Optional.of(readTurn(connection, result));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM memory_turns WHERE id = ?")) {
            statement.setString(1, candidate.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTurn(connection, result)) : Optional.empty();
            }
        }
    }

    private static void upsertJob(Connection connection, PipelineJob job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            statement.setString(1, job.id());
            statement.setString(2, job.turnId());
            setScope(statement, 3, job.scope());
            statement.setString(8, job.stage().name());
            statement.setString(9, job.status().name());
            statement.setInt(10, job.attempts());
            statement.setString(11, job.error());
            statement.setLong(12, epochMillis(job.updatedAt()));
            statement.executeUpdate();
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
        String businessKey = result.getString("business_key");
        if (businessKey == null || businessKey.isBlank()) businessKey = id;
        return new CompletedTurn(
                id, businessKey, readScope(result),
                result.getString("user_input"), result.getString("assistant_output"),
                outputs, instant(result, "completed_at"));
    }

    private static AtomicMemory readAtomic(Connection connection, ResultSet result) throws SQLException {
        String memoryId = result.getString("id");
        List<String> sources = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_id FROM memory_atomic_sources
                WHERE memory_id = ? ORDER BY created_at ASC, source_id ASC
                """)) {
            statement.setString(1, memoryId);
            try (ResultSet sourceRows = statement.executeQuery()) {
                while (sourceRows.next()) sources.add(sourceRows.getString(1));
            }
        }
        long expiresMillis = result.getLong("expires_at");
        Instant expiresAt = result.wasNull() ? null : Instant.ofEpochMilli(expiresMillis);
        return new AtomicMemory(
                memoryId, readScope(result), MemoryType.valueOf(result.getString("memory_type")),
                result.getString("content"), result.getDouble("confidence"), result.getInt("priority"),
                result.getInt("version"), result.getString("source_turn_id"),
                sources, MemoryStatus.valueOf(result.getString("status")),
                instant(result, "valid_from"), expiresAt, result.getString("superseded_by_id"),
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

    private static byte[] encodeVector(double[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Double.BYTES);
        for (double value : values) buffer.putDouble(value);
        return buffer.array();
    }

    private static double[] decodeVector(byte[] bytes, int dimension) throws SQLException {
        if (dimension <= 0 || bytes == null || bytes.length != dimension * Double.BYTES) {
            throw new SQLException("invalid persisted vector dimension or byte length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        double[] values = new double[dimension];
        for (int index = 0; index < dimension; index++) values[index] = buffer.getDouble();
        return values;
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
