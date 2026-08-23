package com.github.agentos.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 线程安全的进程内 L0-L3 存储，也是文件存储的内存索引基类。 */
public class InMemoryMemoryStore implements MemoryStore {

    protected final Map<String, CompletedTurn> turns = new LinkedHashMap<>();
    protected final Map<String, AtomicMemory> atomicMemories = new LinkedHashMap<>();
    protected final Map<String, ScenarioMemory> scenarios = new LinkedHashMap<>();
    protected final Map<String, ProfileMemory> profiles = new LinkedHashMap<>();
    protected final Map<String, PipelineJob> jobs = new LinkedHashMap<>();
    protected final Map<String, MemoryVector> vectors = new LinkedHashMap<>();

    @Override
    public synchronized PipelineJob captureTurn(CompletedTurn turn) {
        CompletedTurn stored = findByBusinessKey(turn).orElseGet(() -> {
            turns.putIfAbsent(turn.id(), turn);
            return turns.get(turn.id());
        });
        String jobId = "pipeline:" + stored.id();
        PipelineJob existing = jobs.get(jobId);
        if (existing != null && existing.status() == PipelineJob.Status.COMPLETED) return existing;
        PipelineJob job = existing == null
                ? PipelineJob.pending(stored.id(), stored.scope())
                : existing.retry();
        jobs.put(job.id(), job);
        onMutation();
        return job;
    }

    @Override
    public synchronized void saveTurn(CompletedTurn turn) {
        if (findByBusinessKey(turn).isEmpty()) turns.putIfAbsent(turn.id(), turn);
        onMutation();
    }

    @Override
    public synchronized Optional<CompletedTurn> findTurn(String turnId) {
        return Optional.ofNullable(turns.get(turnId));
    }

    @Override
    public synchronized List<CompletedTurn> listRecentTurns(MemoryScope scope, int limit) {
        if (limit <= 0) return List.of();
        List<CompletedTurn> selected = turns.values().stream()
                .filter(turn -> turn.scope().sameConversation(scope))
                .sorted(Comparator.comparing(CompletedTurn::completedAt).reversed())
                .limit(limit)
                .sorted(Comparator.comparing(CompletedTurn::completedAt))
                .toList();
        return List.copyOf(selected);
    }

    @Override
    public synchronized void upsertAtomic(AtomicMemory memory) {
        AtomicMemory current = atomicMemories.get(memory.id());
        if (current == null || memory.version() >= current.version()) {
            atomicMemories.put(memory.id(), memory);
            onMutation();
        }
    }

    @Override
    public synchronized Optional<AtomicMemory> findAtomic(String memoryId) {
        return Optional.ofNullable(atomicMemories.get(memoryId));
    }

    @Override
    public synchronized boolean deleteAtomic(String memoryId) {
        AtomicMemory removed = atomicMemories.remove(memoryId);
        vectors.entrySet().removeIf(entry -> entry.getValue().memoryId().equals(memoryId));
        if (removed != null) onMutation();
        return removed != null;
    }

    @Override
    public synchronized int purgeExpired(Instant now) {
        List<String> expired = atomicMemories.values().stream()
                .filter(memory -> memory.expiresAt() != null && !memory.expiresAt().isAfter(now))
                .map(AtomicMemory::id)
                .toList();
        expired.forEach(atomicMemories::remove);
        vectors.entrySet().removeIf(entry -> expired.contains(entry.getValue().memoryId()));
        if (!expired.isEmpty()) onMutation();
        return expired.size();
    }

    @Override
    public synchronized void supersedeAtomic(AtomicMemory previous, AtomicMemory replacement) {
        AtomicMemory current = atomicMemories.get(previous.id());
        if (current == null) throw new IllegalArgumentException("previous memory does not exist");
        if (current.id().equals(replacement.id())) {
            throw new IllegalArgumentException("replacement must have a different id");
        }
        if (!current.scope().sameActor(replacement.scope())) {
            throw new IllegalArgumentException("replacement must belong to the same actor");
        }
        AtomicMemory marked = current.supersedeBy(
                replacement.id(), replacement.sourceTurnId());
        atomicMemories.put(replacement.id(), replacement);
        atomicMemories.put(marked.id(), marked);
        onMutation();
    }

    @Override
    public synchronized List<AtomicMemory> listAtomic(MemoryScope scope) {
        return listAtomic(scope, false);
    }

    @Override
    public synchronized List<AtomicMemory> listAtomic(MemoryScope scope, boolean includeInactive) {
        Instant now = Instant.now();
        return atomicMemories.values().stream()
                .filter(memory -> memory.scope().sameActor(scope))
                .filter(memory -> scope.taskId().isEmpty()
                        || memory.scope().taskId().isEmpty()
                        || scope.taskId().equals(memory.scope().taskId()))
                .filter(memory -> includeInactive || memory.activeAt(now))
                .sorted(Comparator.comparing(AtomicMemory::updatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized void saveVector(MemoryVector vector) {
        String key = vector.memoryId() + '\u0000' + vector.model();
        MemoryVector current = vectors.get(key);
        if (current == null || vector.contentVersion() >= current.contentVersion()) {
            vectors.put(key, vector);
            onMutation();
        }
    }

    @Override
    public synchronized Optional<MemoryVector> findVector(String memoryId, String model) {
        return Optional.ofNullable(vectors.get(memoryId + '\u0000' + model));
    }

    @Override
    public synchronized void saveScenario(ScenarioMemory scenario) {
        ScenarioMemory current = scenarios.get(scenario.id());
        if (current == null || scenario.version() >= current.version()) {
            scenarios.put(scenario.id(), scenario);
            onMutation();
        }
    }

    @Override
    public synchronized List<ScenarioMemory> listScenarios(MemoryScope scope) {
        return scenarios.values().stream()
                .filter(scenario -> scenario.scope().sameScenario(scope))
                .sorted(Comparator.comparing(ScenarioMemory::updatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized void saveProfile(ProfileMemory profile) {
        String key = actorKey(profile.scope());
        ProfileMemory current = profiles.get(key);
        if (current == null || profile.version() >= current.version()) {
            profiles.put(key, profile);
            onMutation();
        }
    }

    @Override
    public synchronized Optional<ProfileMemory> findProfile(MemoryScope scope) {
        return Optional.ofNullable(profiles.get(actorKey(scope)));
    }

    @Override
    public synchronized void saveJob(PipelineJob job) {
        jobs.put(job.id(), job);
        onMutation();
    }

    @Override
    public synchronized Optional<PipelineJob> findJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public synchronized List<PipelineJob> listRecoverableJobs(int maxAttempts) {
        List<PipelineJob> selected = new ArrayList<>();
        for (PipelineJob job : jobs.values()) {
            if (job.status() != PipelineJob.Status.COMPLETED && job.attempts() < maxAttempts) {
                selected.add(job);
            }
        }
        selected.sort(Comparator.comparing(PipelineJob::updatedAt));
        return selected.stream()
                .map(job -> job.status() == PipelineJob.Status.RUNNING ? job.retry() : job)
                .toList();
    }

    /** 文件实现利用该回调在每次成功变更后落盘。 */
    protected void onMutation() {
        // no-op
    }

    private static String actorKey(MemoryScope scope) {
        return scope.teamId() + '\u0000' + scope.userId() + '\u0000' + scope.agentId();
    }

    private Optional<CompletedTurn> findByBusinessKey(CompletedTurn candidate) {
        return turns.values().stream()
                .filter(turn -> turn.scope().sameActor(candidate.scope()))
                .filter(turn -> turn.businessKey().equals(candidate.businessKey()))
                .findFirst();
    }
}
