package com.github.agentos.memory;

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

    @Override
    public synchronized void saveTurn(CompletedTurn turn) {
        turns.putIfAbsent(turn.id(), turn);
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
    public synchronized List<AtomicMemory> listAtomic(MemoryScope scope) {
        return atomicMemories.values().stream()
                .filter(memory -> memory.scope().sameActor(scope))
                .filter(memory -> scope.taskId().isEmpty()
                        || memory.scope().taskId().isEmpty()
                        || scope.taskId().equals(memory.scope().taskId()))
                .sorted(Comparator.comparing(AtomicMemory::updatedAt).reversed())
                .toList();
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
        List<PipelineJob> result = new ArrayList<>();
        for (PipelineJob job : jobs.values()) {
            if (job.status() != PipelineJob.Status.COMPLETED && job.attempts() < maxAttempts) {
                result.add(job.status() == PipelineJob.Status.RUNNING ? job.retry() : job);
            }
        }
        result.sort(Comparator.comparing(PipelineJob::updatedAt));
        return List.copyOf(result);
    }

    /** 文件实现利用该回调在每次成功变更后落盘。 */
    protected void onMutation() {
        // no-op
    }

    private static String actorKey(MemoryScope scope) {
        return scope.teamId() + '\u0000' + scope.userId() + '\u0000' + scope.agentId();
    }
}
