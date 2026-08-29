package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.agentos.memory.AtomicMemory;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryStatus;
import com.github.agentos.memory.MemoryStore;
import com.github.agentos.memory.MemoryVector;
import com.github.agentos.memory.PipelineJob;
import com.github.agentos.memory.ProfileMemory;
import com.github.agentos.memory.ScenarioMemory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL + MyBatis-Plus 的 L0-L3 统一记忆存储。 */
public class MybatisMemoryStore implements MemoryStore {

    private static final String TURN = "TURN";
    private static final String ATOMIC = "ATOMIC";
    private static final String VECTOR = "VECTOR";
    private static final String SCENARIO = "SCENARIO";
    private static final String PROFILE = "PROFILE";
    private static final String JOB = "JOB";

    private final MemoryRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisMemoryStore(MemoryRecordMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public PipelineJob captureTurn(CompletedTurn turn) {
        saveTurn(turn);
        String jobId = "pipeline:" + turn.id();
        PipelineJob existing = findJob(jobId).orElse(null);
        if (existing != null && existing.status() == PipelineJob.Status.COMPLETED) {
            return existing;
        }
        PipelineJob job = existing == null
                ? PipelineJob.pending(turn.id(), turn.scope()) : existing.retry();
        saveJob(job);
        return job;
    }

    @Override
    public void saveTurn(CompletedTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        String key = key(TURN, turn.id());
        if (mapper.selectById(key) != null) return;
        insertIgnoringDuplicate(row(
                key, TURN, turn.id(), turn.scope(), 1, "ACTIVE", turn.completedAt(), null,
                write(turn), turn.completedAt(), turn.completedAt()));
    }

    @Override
    public Optional<CompletedTurn> findTurn(String turnId) {
        return readOptional(key(TURN, requireText(turnId, "turnId")), CompletedTurn.class);
    }

    @Override
    public List<CompletedTurn> listRecentTurns(MemoryScope scope, int limit) {
        if (limit <= 0) return List.of();
        Objects.requireNonNull(scope, "scope must not be null");
        List<CompletedTurn> descending = actorRows(TURN, scope).stream()
                .map(row -> read(row.payload(), CompletedTurn.class))
                .filter(turn -> turn.scope().sameConversation(scope))
                .sorted(Comparator.comparing(CompletedTurn::completedAt).reversed())
                .limit(limit)
                .toList();
        List<CompletedTurn> chronological = new ArrayList<>(descending);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    @Override
    public void upsertAtomic(AtomicMemory memory) {
        Objects.requireNonNull(memory, "memory must not be null");
        String key = key(ATOMIC, memory.id());
        PersistenceRows.MemoryRecordRow current = mapper.selectById(key);
        if (current != null && current.recordVersion() > memory.version()) return;
        PersistenceRows.MemoryRecordRow next = row(
                key, ATOMIC, memory.id(), memory.scope(), memory.version(),
                memory.status().name(), memory.updatedAt(), memory.expiresAt(), write(memory),
                current == null ? memory.createdAt() : current.createdAt(), memory.updatedAt());
        save(next, current != null);
    }

    @Override
    public Optional<AtomicMemory> findAtomic(String memoryId) {
        return readOptional(key(ATOMIC, requireText(memoryId, "memoryId")), AtomicMemory.class);
    }

    @Override
    @Transactional
    public boolean deleteAtomic(String memoryId) {
        String id = requireText(memoryId, "memoryId");
        int deleted = mapper.deleteById(key(ATOMIC, id));
        List<String> vectorKeys = mapper.selectList(
                        new QueryWrapper<PersistenceRows.MemoryRecordRow>()
                                .eq("record_kind", VECTOR).eq("business_id", id))
                .stream().map(PersistenceRows.MemoryRecordRow::recordKey).toList();
        if (!vectorKeys.isEmpty()) mapper.deleteByIds(vectorKeys);
        return deleted > 0;
    }

    @Override
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        List<String> keys = mapper.selectList(new QueryWrapper<PersistenceRows.MemoryRecordRow>()
                        .eq("record_kind", ATOMIC)
                        .le("expires_at", now))
                .stream().map(PersistenceRows.MemoryRecordRow::recordKey).toList();
        if (keys.isEmpty()) return 0;
        mapper.deleteByIds(keys);
        return keys.size();
    }

    @Override
    @Transactional
    public void supersedeAtomic(AtomicMemory previous, AtomicMemory replacement) {
        upsertAtomic(replacement);
        upsertAtomic(previous.supersedeBy(replacement.id(), replacement.sourceTurnId()));
    }

    @Override
    public List<AtomicMemory> listAtomic(MemoryScope scope) {
        return listAtomic(scope, false);
    }

    @Override
    public List<AtomicMemory> listAtomic(MemoryScope scope, boolean includeInactive) {
        Objects.requireNonNull(scope, "scope must not be null");
        Instant now = Instant.now();
        return actorRows(ATOMIC, scope).stream()
                .map(row -> read(row.payload(), AtomicMemory.class))
                .filter(memory -> taskVisible(memory.scope(), scope))
                .filter(memory -> includeInactive || memory.activeAt(now))
                .sorted(Comparator.comparing(AtomicMemory::updatedAt).reversed())
                .toList();
    }

    @Override
    public void saveVector(MemoryVector vector) {
        Objects.requireNonNull(vector, "vector must not be null");
        AtomicMemory memory = findAtomic(vector.memoryId()).orElseThrow(() ->
                new IllegalArgumentException("unknown atomic memory: " + vector.memoryId()));
        String key = vectorKey(vector.memoryId(), vector.model());
        PersistenceRows.MemoryRecordRow current = mapper.selectById(key);
        VectorPayload payload = new VectorPayload(
                vector.memoryId(), vector.model(), vector.contentVersion(),
                vector.values(), vector.updatedAt());
        PersistenceRows.MemoryRecordRow next = row(
                key, VECTOR, vector.memoryId(), memory.scope(), vector.contentVersion(),
                "ACTIVE", vector.updatedAt(), null, write(payload),
                current == null ? vector.updatedAt() : current.createdAt(), vector.updatedAt());
        save(next, current != null);
    }

    @Override
    public Optional<MemoryVector> findVector(String memoryId, String model) {
        String key = vectorKey(requireText(memoryId, "memoryId"), requireText(model, "model"));
        return readOptional(key, VectorPayload.class).map(VectorPayload::toDomain);
    }

    @Override
    public void saveScenario(ScenarioMemory scenario) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        String key = key(SCENARIO, scenario.id());
        PersistenceRows.MemoryRecordRow current = mapper.selectById(key);
        if (current != null && current.recordVersion() > scenario.version()) return;
        save(row(key, SCENARIO, scenario.id(), scenario.scope(), scenario.version(), "ACTIVE",
                scenario.updatedAt(), null, write(scenario),
                current == null ? scenario.updatedAt() : current.createdAt(), scenario.updatedAt()),
                current != null);
    }

    @Override
    public List<ScenarioMemory> listScenarios(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        return actorRows(SCENARIO, scope).stream()
                .map(row -> read(row.payload(), ScenarioMemory.class))
                .filter(memory -> taskVisible(memory.scope(), scope))
                .sorted(Comparator.comparing(ScenarioMemory::updatedAt).reversed())
                .toList();
    }

    @Override
    public void saveProfile(ProfileMemory profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        String key = profileKey(profile.scope());
        PersistenceRows.MemoryRecordRow current = mapper.selectById(key);
        if (current != null && current.recordVersion() > profile.version()) return;
        save(row(key, PROFILE, profile.id(), profile.scope(), profile.version(), "ACTIVE",
                profile.updatedAt(), null, write(profile),
                current == null ? profile.updatedAt() : current.createdAt(), profile.updatedAt()),
                current != null);
    }

    @Override
    public Optional<ProfileMemory> findProfile(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        return readOptional(profileKey(scope), ProfileMemory.class);
    }

    @Override
    public void saveJob(PipelineJob job) {
        Objects.requireNonNull(job, "job must not be null");
        String key = key(JOB, job.id());
        PersistenceRows.MemoryRecordRow current = mapper.selectById(key);
        save(row(key, JOB, job.id(), job.scope(), Math.max(1, job.attempts() + 1),
                job.status().name(), job.updatedAt(), null, write(job),
                current == null ? job.updatedAt() : current.createdAt(), job.updatedAt()),
                current != null);
    }

    @Override
    public Optional<PipelineJob> findJob(String jobId) {
        return readOptional(key(JOB, requireText(jobId, "jobId")), PipelineJob.class);
    }

    @Override
    public List<PipelineJob> listRecoverableJobs(int maxAttempts) {
        if (maxAttempts <= 0) return List.of();
        return mapper.selectList(new QueryWrapper<PersistenceRows.MemoryRecordRow>()
                        .eq("record_kind", JOB).orderByAsc("sort_at"))
                .stream().map(row -> read(row.payload(), PipelineJob.class))
                .filter(job -> job.status() != PipelineJob.Status.COMPLETED)
                .filter(job -> job.attempts() < maxAttempts)
                .map(job -> job.status() == PipelineJob.Status.RUNNING ? job.retry() : job)
                .toList();
    }

    private List<PersistenceRows.MemoryRecordRow> actorRows(String kind, MemoryScope scope) {
        return mapper.selectList(new QueryWrapper<PersistenceRows.MemoryRecordRow>()
                .eq("record_kind", kind)
                .eq("team_id", scope.teamId())
                .eq("user_id", scope.userId())
                .eq("agent_id", scope.agentId())
                .orderByDesc("sort_at"));
    }

    private <T> Optional<T> readOptional(String key, Class<T> type) {
        PersistenceRows.MemoryRecordRow row = mapper.selectById(key);
        return row == null ? Optional.empty() : Optional.of(read(row.payload(), type));
    }

    private void save(PersistenceRows.MemoryRecordRow row, boolean exists) {
        if (exists) {
            mapper.updateById(row);
        } else {
            insertIgnoringDuplicate(row);
        }
    }

    private void insertIgnoringDuplicate(PersistenceRows.MemoryRecordRow row) {
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // Idempotent write: another worker persisted this logical object first.
        }
    }

    private PersistenceRows.MemoryRecordRow row(
            String key,
            String kind,
            String businessId,
            MemoryScope scope,
            int version,
            String status,
            Instant sortAt,
            Instant expiresAt,
            String payload,
            Instant createdAt,
            Instant updatedAt) {
        return new PersistenceRows.MemoryRecordRow(
                key, kind, businessId, scope.teamId(), scope.userId(), scope.agentId(),
                scope.sessionId(), scope.taskId(), version, status, sortAt, expiresAt,
                payload, createdAt, updatedAt);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to encode memory payload", exception);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to decode memory payload as "
                    + type.getSimpleName(), exception);
        }
    }

    private static boolean taskVisible(MemoryScope stored, MemoryScope requested) {
        return stored.taskId().isEmpty() || requested.taskId().isEmpty()
                || stored.taskId().equals(requested.taskId());
    }

    private static String key(String kind, String businessId) {
        return kind + ':' + businessId;
    }

    private static String vectorKey(String memoryId, String model) {
        String material = memoryId + '\u0000' + model;
        return key(VECTOR, UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8)).toString());
    }

    private static String profileKey(MemoryScope scope) {
        String material = scope.teamId() + '\u0000' + scope.userId() + '\u0000' + scope.agentId();
        return key(PROFILE, UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8)).toString());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private record VectorPayload(
            String memoryId,
            String model,
            int contentVersion,
            double[] values,
            Instant updatedAt) {

        private MemoryVector toDomain() {
            return new MemoryVector(memoryId, model, contentVersion, values, updatedAt);
        }
    }
}
