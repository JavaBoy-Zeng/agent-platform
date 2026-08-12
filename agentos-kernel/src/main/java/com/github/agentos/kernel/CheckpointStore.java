package com.github.agentos.kernel;

import java.util.Optional;

/** Invocation Checkpoint 的持久化协议。 */
public interface CheckpointStore {
    void save(AgentCheckpoint checkpoint);
    Optional<AgentCheckpoint> load(String invocationId);
    void delete(String invocationId);
}
