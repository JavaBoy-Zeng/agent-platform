package com.github.agentos.memory;

import java.util.List;
import java.util.Optional;

/** L0-L3 数据和 Pipeline 状态的统一持久化端口。 */
public interface MemoryStore {

    void saveTurn(CompletedTurn turn);

    Optional<CompletedTurn> findTurn(String turnId);

    List<CompletedTurn> listRecentTurns(MemoryScope scope, int limit);

    void upsertAtomic(AtomicMemory memory);

    List<AtomicMemory> listAtomic(MemoryScope scope);

    void saveScenario(ScenarioMemory scenario);

    List<ScenarioMemory> listScenarios(MemoryScope scope);

    void saveProfile(ProfileMemory profile);

    Optional<ProfileMemory> findProfile(MemoryScope scope);

    void saveJob(PipelineJob job);

    Optional<PipelineJob> findJob(String jobId);

    List<PipelineJob> listRecoverableJobs(int maxAttempts);
}
