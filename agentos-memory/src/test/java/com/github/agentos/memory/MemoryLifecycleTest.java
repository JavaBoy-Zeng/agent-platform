package com.github.agentos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLifecycleTest {

    @TempDir
    Path directory;

    @Test
    void supportsCorrectionInvalidationSupersedeTtlAndDeletion() {
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");
        SqliteMemoryStore store = new SqliteMemoryStore(directory.resolve("lifecycle.sqlite"));
        try (MemoryService service = new MemoryService(
                store, new RuleBasedMemoryModel(), new HashingMemoryEmbedding(),
                MemoryRecallPolicy.defaults())) {
            AtomicMemory expiring = service.rememberFact(scope, "Java 21 is required", Duration.ofHours(1));
            assertThat(expiring.status()).isEqualTo(MemoryStatus.ACTIVE);
            assertThat(expiring.expiresAt()).isAfter(Instant.now());

            Instant revisedExpiry = Instant.now().plus(Duration.ofHours(2));
            AtomicMemory corrected = service.correctAtomic(
                    scope, expiring.id(), "Java 22 is required", revisedExpiry);
            assertThat(corrected.content()).isEqualTo("Java 22 is required");
            assertThat(corrected.expiresAt()).isEqualTo(revisedExpiry);
            assertThat(corrected.sourceTurnIds()).hasSizeGreaterThanOrEqualTo(2);

            AtomicMemory invalidated = service.invalidateAtomic(scope, corrected.id());
            assertThat(invalidated.status()).isEqualTo(MemoryStatus.INVALIDATED);
            assertThat(service.atomicMemories(scope)).isEmpty();
            assertThat(service.atomicMemories(scope, true)).extracting(AtomicMemory::id)
                    .contains(corrected.id());

            AtomicMemory previous = service.rememberFact(scope, "Deployment region is east");
            AtomicMemory replacement = service.supersedeAtomic(
                    scope, previous.id(), "Deployment region is west", null);
            AtomicMemory superseded = service.findAtomic(previous.id());
            assertThat(superseded.status()).isEqualTo(MemoryStatus.SUPERSEDED);
            assertThat(superseded.supersededById()).isEqualTo(replacement.id());
            assertThat(service.atomicMemories(scope)).extracting(AtomicMemory::id)
                    .contains(replacement.id()).doesNotContain(previous.id());

            assertThat(service.deleteAtomic(scope, replacement.id())).isTrue();
            assertThat(service.findAtomic(replacement.id())).isNull();

            Instant past = Instant.parse("2025-01-02T00:00:00Z");
            AtomicMemory expired = new AtomicMemory(
                    "expired", scope, MemoryType.FACT, "expired fact", 1.0, 5, 1,
                    "source", List.of("source"), MemoryStatus.ACTIVE,
                    past.minusSeconds(60), past, "", past.minusSeconds(60), past.minusSeconds(60));
            store.upsertAtomic(expired);
            assertThat(store.listAtomic(scope)).extracting(AtomicMemory::id).doesNotContain("expired");
            assertThat(service.purgeExpired(Instant.now())).isEqualTo(1);
            assertThat(store.findAtomic("expired")).isEmpty();
        }
    }

    @Test
    void reusesPersistedDocumentVectorsAcrossServiceRestarts() {
        Path database = directory.resolve("vectors.sqlite");
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");
        CountingEmbedding writerEmbedding = new CountingEmbedding();
        String memoryId;

        try (MemoryService writer = new MemoryService(
                new SqliteMemoryStore(database), new RuleBasedMemoryModel(), writerEmbedding,
                MemoryRecallPolicy.defaults())) {
            memoryId = writer.rememberFact(scope, "Java is the preferred language").id();
            assertThat(writerEmbedding.calls()).isEqualTo(1);
            assertThat(writer.recall(scope, "preferred language").atomicMemories()).isNotEmpty();
            assertThat(writerEmbedding.calls()).isEqualTo(2);
        }

        CountingEmbedding readerEmbedding = new CountingEmbedding();
        SqliteMemoryStore reopened = new SqliteMemoryStore(database);
        assertThat(reopened.findVector(memoryId, readerEmbedding.modelId())).isPresent();
        try (MemoryService reader = new MemoryService(
                reopened, new RuleBasedMemoryModel(), readerEmbedding,
                MemoryRecallPolicy.defaults())) {
            assertThat(reader.recall(scope, "preferred language").atomicMemories()).isNotEmpty();
            assertThat(readerEmbedding.calls()).isEqualTo(1);
        }
    }

    @Test
    void derivesStableTurnIdAndUsesBusinessKeyForOutboxIdempotency() {
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");
        CompletedTurn first = CompletedTurn.success(
                scope, "run-42", "question", "first", List.of());
        CompletedTurn retry = CompletedTurn.success(
                scope, "run-42", "question", "retry", List.of());
        assertThat(retry.id()).isEqualTo(first.id());
        CompletedTurn transportRetry = new CompletedTurn(
                "transport-retry-id", "run-42", scope, "question", "retry",
                List.of(), Instant.now());

        for (MemoryStore store : List.of(
                new InMemoryMemoryStore(),
                new SqliteMemoryStore(directory.resolve("outbox.sqlite")))) {
            PipelineJob firstJob = store.captureTurn(first);
            PipelineJob retryJob = store.captureTurn(transportRetry);
            assertThat(retryJob.id()).isEqualTo(firstJob.id());
            assertThat(store.listRecentTurns(scope, 10)).singleElement()
                    .satisfies(turn -> assertThat(turn.assistantOutput()).isEqualTo("first"));
            assertThat(store.findJob("pipeline:" + first.id())).isPresent();
        }
    }

    @Test
    void automaticallySupersedesAnExplicitlyConflictingFact() {
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");
        try (MemoryService service = MemoryService.inMemory()) {
            service.capture(CompletedTurn.success(
                    scope, "run-1", "The deployment uses Java 21.", "noted", List.of()));
            assertThat(service.awaitIdle(Duration.ofSeconds(2))).isTrue();
            AtomicMemory previous = service.atomicMemories(scope).getFirst();

            service.capture(CompletedTurn.success(
                    scope, "run-2", "The deployment now uses Java 22.", "updated", List.of()));
            assertThat(service.awaitIdle(Duration.ofSeconds(2))).isTrue();

            assertThat(service.atomicMemories(scope)).singleElement()
                    .satisfies(memory -> assertThat(memory.content()).contains("Java 22"));
            AtomicMemory old = service.findAtomic(previous.id());
            assertThat(old.status()).isEqualTo(MemoryStatus.SUPERSEDED);
            assertThat(old.supersededById()).isNotBlank();
            assertThat(service.atomicMemories(scope, true)).hasSize(2);
        }
    }

    private static final class CountingEmbedding implements MemoryEmbedding {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public double[] embed(String text) {
            calls.incrementAndGet();
            return new double[] {1.0, text.length()};
        }

        @Override
        public String modelId() {
            return "counting-v1";
        }

        int calls() {
            return calls.get();
        }
    }
}
