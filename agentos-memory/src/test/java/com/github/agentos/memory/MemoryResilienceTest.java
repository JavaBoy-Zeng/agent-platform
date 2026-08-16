package com.github.agentos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryResilienceTest {

    @TempDir
    Path directory;

    @Test
    void degradesRecallWhenEmbeddingTimesOut() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryScope scope = scope();
        store.upsertAtomic(new AtomicMemory(
                "atomic", scope, MemoryType.FACT, "Java", 0.9, 8, 1,
                "turn", Instant.now(), Instant.now()));
        MemoryEmbedding slowEmbedding = text -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MemoryAdapterException("interrupted", exception);
            }
            return new double[]{1};
        };

        try (MemoryService service = new MemoryService(
                store, new RuleBasedMemoryModel(), slowEmbedding,
                new MemoryRecallPolicy(2, 2, 1, 100, 500, 20))) {
            MemoryContext context = service.recall(scope, "Java");
            assertThat(context.degraded()).isTrue();
            assertThat(context.formattedContext()).isEmpty();
        }
    }

    @Test
    void rejectsTruncatedOrCorruptedFileState() throws Exception {
        Files.write(directory.resolve("memory-state.bin"), new byte[]{0x41, 0x47});

        assertThatThrownBy(() -> new FileMemoryStore(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initialize memory store")
                .hasRootCauseInstanceOf(java.io.EOFException.class);
    }

    @Test
    void respectsPerBlockAndTotalFormattingBudgets() {
        MemoryScope scope = scope();
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.saveTurn(new CompletedTurn(
                "turn", scope, "x".repeat(300), "y".repeat(300), List.of(), Instant.now()));

        try (MemoryService service = new MemoryService(
                store, new RuleBasedMemoryModel(), new HashingMemoryEmbedding(),
                new MemoryRecallPolicy(2, 2, 1, 80, 180, 1_000))) {
            MemoryContext context = service.recall(scope, "query");
            assertThat(context.degraded()).isFalse();
            assertThat(context.formattedContext().length()).isLessThanOrEqualTo(180);
            assertThat(context.formattedContext()).endsWith("</memory_context>");
        }
    }

    private static MemoryScope scope() {
        return new MemoryScope("team", "user", "agent", "session", "task");
    }
}
