package com.github.agentos.kernel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 基于并发 Map 的进程内 CheckpointStore。 */
public final class InMemoryCheckpointStore implements CheckpointStore {
    private final java.util.Map<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override public void save(AgentCheckpoint checkpoint) {
        checkpoints.put(checkpoint.invocationId(), checkpoint);
    }

    @Override public Optional<AgentCheckpoint> load(String invocationId) {
        return Optional.ofNullable(checkpoints.get(invocationId));
    }

    @Override public void delete(String invocationId) {
        checkpoints.remove(invocationId);
    }
}
