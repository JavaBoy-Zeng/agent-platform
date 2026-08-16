package com.github.agentos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SqliteMemoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void migratesOnceCreatesIndexesAndReloadsCompleteState() throws Exception {
        Path database = directory.resolve("memory.sqlite");
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");

        SqliteMemoryStore first = new SqliteMemoryStore(database);
        assertThat(first.schemaVersion()).isEqualTo(2);
        try (MemoryService service = new MemoryService(
                first, new RuleBasedMemoryModel(), new HashingMemoryEmbedding(),
                MemoryRecallPolicy.defaults())) {
            service.capture(CompletedTurn.success(
                    scope, "I prefer Java.", "Preference noted.", List.of("build passed")));
            assertThat(service.awaitIdle(Duration.ofSeconds(3))).isTrue();
        }

        SqliteMemoryStore reopened = new SqliteMemoryStore(database);
        assertThat(reopened.schemaVersion()).isEqualTo(2);
        assertThat(reopened.listRecentTurns(scope, 10)).singleElement()
                .satisfies(turn -> assertThat(turn.toolOutputs()).containsExactly("build passed"));
        assertThat(reopened.listAtomic(scope)).isNotEmpty();
        assertThat(reopened.listScenarios(scope)).isNotEmpty();
        assertThat(reopened.findProfile(scope)).isPresent();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'index' AND name LIKE 'idx_memory_%'
                        ORDER BY name
                        """);
                var result = statement.executeQuery()) {
            List<String> indexes = new ArrayList<>();
            while (result.next()) indexes.add(result.getString(1));
            assertThat(indexes).containsExactly(
                    "idx_memory_atomic_scope_updated",
                    "idx_memory_pipeline_recovery",
                    "idx_memory_scenarios_scope_updated",
                    "idx_memory_turns_scope_completed");
        }
    }

    @Test
    void keepsRepresentativeWorkloadWithinRegressionBudget() {
        Path database = directory.resolve("performance.sqlite");
        SqliteMemoryStore store = new SqliteMemoryStore(database);
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int index = 0; index < 300; index++) {
                store.saveTurn(new CompletedTurn(
                        "turn-" + index, scope, "input-" + index, "output-" + index,
                        List.of(), java.time.Instant.ofEpochMilli(index)));
                store.upsertAtomic(new AtomicMemory(
                        "atomic-" + index, scope, MemoryType.FACT, "fact-" + index,
                        0.8, 6, 1, "turn-" + index,
                        java.time.Instant.ofEpochMilli(index), java.time.Instant.ofEpochMilli(index)));
            }
            for (int index = 0; index < 50; index++) {
                assertThat(store.listRecentTurns(scope, 20)).hasSize(20);
                assertThat(store.listAtomic(scope)).hasSize(300);
            }
        });
    }
}
