package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 文件 Checkpoint 的跨实例恢复测试。 */
class FileCheckpointStoreTest {

    @TempDir Path directory;

    @Test
    void reloadsCheckpointFromAnotherStoreInstance() {
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "session-1", "invocation-1", "main-agent", "task-1",
                "default", "anonymous", "continue work", "plan-1", "step-2", 1,
                List.of("step-1"), Map.of("observation", "done"),
                new PendingAction("approval-1", PendingActionType.HUMAN_APPROVAL,
                        "approve", "continue", Map.of("toolName", "writer")),
                new ExecutionCounters(2, 1, 0, 1), AgentRunStatus.WAITING, Instant.now());

        new FileCheckpointStore(directory).save(checkpoint);
        AgentCheckpoint reloaded = new FileCheckpointStore(directory)
                .load("invocation-1").orElseThrow();

        assertThat(reloaded.currentStepIndex()).isEqualTo(1);
        assertThat(reloaded.completedStepIds()).containsExactly("step-1");
        assertThat(reloaded.pendingAction().payload()).containsEntry("toolName", "writer");
        assertThat(reloaded.executionCounters()).isEqualTo(checkpoint.executionCounters());
    }
}
