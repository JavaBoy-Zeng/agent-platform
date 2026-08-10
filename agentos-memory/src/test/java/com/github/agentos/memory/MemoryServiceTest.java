package com.github.agentos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    @Test
    void buildsAndRecallsL0ThroughL3AcrossSessions() {
        MemoryScope firstSession = new MemoryScope("team-1", "user-1", "agent-1", "session-1", "coding");
        MemoryScope nextSession = new MemoryScope("team-1", "user-1", "agent-1", "session-2", "coding");

        try (MemoryService service = MemoryService.inMemory()) {
            service.capture(CompletedTurn.success(
                    firstSession,
                    "I prefer concise answers. We must use Java.",
                    "I will use Java and keep the answer concise.",
                    List.of("build succeeded")));

            assertThat(service.awaitIdle(Duration.ofSeconds(2))).isTrue();
            assertThat(service.recentTurns(firstSession, 10)).hasSize(1);
            assertThat(service.atomicMemories(nextSession)).isNotEmpty();
            assertThat(service.scenarios(nextSession)).isNotEmpty();
            assertThat(service.profile(nextSession)).isNotNull();

            MemoryContext context = service.recall(nextSession, "Which language should we use?");
            assertThat(context.degraded()).isFalse();
            assertThat(context.recentTurns()).isEmpty();
            assertThat(context.optionalProfile()).isPresent();
            assertThat(context.formattedContext()).contains("L3", "Java");
        }
    }

    @Test
    void reloadsTheCompleteMemoryStateFromDisk(@TempDir Path directory) {
        MemoryScope scope = new MemoryScope("team-1", "user-1", "agent-1", "session-1", "coding");

        try (MemoryService writer = MemoryService.persistent(directory)) {
            writer.capture(CompletedTurn.success(
                    scope, "I prefer Java.", "Preference noted.", List.of()));
            assertThat(writer.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }

        try (MemoryService reader = MemoryService.persistent(directory)) {
            assertThat(reader.recentTurns(scope, 10)).hasSize(1);
            assertThat(reader.atomicMemories(scope)).isNotEmpty();
            assertThat(reader.scenarios(scope)).isNotEmpty();
            assertThat(reader.profile(scope)).isNotNull();
        }
    }
}
