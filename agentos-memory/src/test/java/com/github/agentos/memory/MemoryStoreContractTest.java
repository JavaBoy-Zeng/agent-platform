package com.github.agentos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreContractTest {

    @TempDir
    Path directory;

    @Test
    void enforcesConversationActorAndTaskScopeBoundaries() {
        for (MemoryStore store : stores("scope")) {
            MemoryScope base = scope("team-a", "user-a", "agent-a", "session-a", "task-a");
            List<MemoryScope> foreignScopes = List.of(
                    scope("team-b", "user-a", "agent-a", "session-a", "task-a"),
                    scope("team-a", "user-b", "agent-a", "session-a", "task-a"),
                    scope("team-a", "user-a", "agent-b", "session-a", "task-a"),
                    scope("team-a", "user-a", "agent-a", "session-b", "task-a"),
                    scope("team-a", "user-a", "agent-a", "session-a", "task-b"));

            store.saveTurn(turn("base", base, "base"));
            for (int index = 0; index < foreignScopes.size(); index++) {
                store.saveTurn(turn("foreign-" + index, foreignScopes.get(index), "foreign"));
            }
            assertThat(store.listRecentTurns(base, 20))
                    .extracting(CompletedTurn::id)
                    .containsExactly("base");

            AtomicMemory taskMemory = atomic("atomic-task", base, 1, "task a");
            AtomicMemory globalMemory = atomic("atomic-global", withTask(base, ""), 1, "global");
            AtomicMemory otherTaskMemory = atomic("atomic-other", withTask(base, "task-b"), 1, "task b");
            AtomicMemory otherActorMemory = atomic(
                    "atomic-foreign", scope("team-a", "user-b", "agent-a", "session-a", "task-a"), 1, "foreign");
            List.of(taskMemory, globalMemory, otherTaskMemory, otherActorMemory).forEach(store::upsertAtomic);

            assertThat(store.listAtomic(base))
                    .extracting(AtomicMemory::id)
                    .containsExactlyInAnyOrder("atomic-task", "atomic-global");
            assertThat(store.listAtomic(withTask(base, "")))
                    .extracting(AtomicMemory::id)
                    .containsExactlyInAnyOrder("atomic-task", "atomic-global", "atomic-other");

            store.saveScenario(scenario("scenario-a", base, 1, "A"));
            store.saveScenario(scenario("scenario-b", withTask(base, "task-b"), 1, "B"));
            assertThat(store.listScenarios(base)).extracting(ScenarioMemory::id).containsExactly("scenario-a");

            store.saveProfile(profile("profile-a", base, 1, "actor profile"));
            assertThat(store.findProfile(scope("team-a", "user-a", "agent-a", "another", "task-b")))
                    .isPresent();
            assertThat(store.findProfile(scope("team-a", "user-b", "agent-a", "another", "task-b")))
                    .isEmpty();
        }
    }

    @Test
    void preservesIdempotencyAndRejectsStaleVersions() {
        for (MemoryStore store : stores("versions")) {
            MemoryScope scope = scope("team", "user", "agent", "session", "task");
            store.saveTurn(turn("turn", scope, "original"));
            store.saveTurn(turn("turn", scope, "replacement"));
            assertThat(store.findTurn("turn").orElseThrow().assistantOutput()).isEqualTo("original");

            store.upsertAtomic(atomic("atomic", scope, 3, "new"));
            store.upsertAtomic(atomic("atomic", scope, 2, "stale"));
            assertThat(store.listAtomic(scope).getFirst().content()).isEqualTo("new");

            store.saveScenario(scenario("scenario", scope, 3, "new"));
            store.saveScenario(scenario("scenario", scope, 2, "stale"));
            assertThat(store.listScenarios(scope).getFirst().content()).isEqualTo("new");

            store.saveProfile(profile("profile", scope, 3, "new"));
            store.saveProfile(profile("profile", scope, 2, "stale"));
            assertThat(store.findProfile(scope).orElseThrow().content()).isEqualTo("new");
        }
    }

    @Test
    void returnsRecoverableJobsInOrderAndNormalizesInterruptedRunningJobs() {
        for (MemoryStore store : stores("jobs")) {
            MemoryScope scope = scope("team", "user", "agent", "session", "task");
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            PipelineJob running = job("running", scope, PipelineJob.Status.RUNNING, 1, base);
            PipelineJob failed = job("failed", scope, PipelineJob.Status.FAILED, 2, base.plusSeconds(1));
            PipelineJob exhausted = job("exhausted", scope, PipelineJob.Status.FAILED, 6, base.plusSeconds(2));
            PipelineJob completed = job("completed", scope, PipelineJob.Status.COMPLETED, 3, base.plusSeconds(3));
            List.of(running, failed, exhausted, completed).forEach(job -> {
                store.saveTurn(turn(job.turnId(), scope, job.turnId()));
                store.saveJob(job);
            });

            List<PipelineJob> recoverable = store.listRecoverableJobs(6);
            assertThat(recoverable).extracting(PipelineJob::id).containsExactly("running", "failed");
            assertThat(recoverable.getFirst().status()).isEqualTo(PipelineJob.Status.PENDING);
            assertThat(recoverable.get(1).status()).isEqualTo(PipelineJob.Status.FAILED);
        }
    }

    @Test
    void handlesConcurrentIdempotentWritesAndVersionRaces() throws Exception {
        for (MemoryStore store : stores("concurrency")) {
            MemoryScope scope = scope("team", "user", "agent", "session", "task");
            int workers = 12;
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int index = 1; index <= workers; index++) {
                    int version = index;
                    futures.add(executor.submit(() -> {
                        start.await();
                        store.saveTurn(turn("shared-turn", scope, "answer-" + version));
                        store.upsertAtomic(atomic("shared-atomic", scope, version, "version-" + version));
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) future.get();
            }

            assertThat(store.listRecentTurns(scope, 20)).hasSize(1);
            AtomicMemory winner = store.listAtomic(scope).getFirst();
            assertThat(winner.version()).isEqualTo(workers);
            assertThat(winner.content()).isEqualTo("version-" + workers);
        }
    }

    private List<MemoryStore> stores(String testName) {
        return List.of(
                new InMemoryMemoryStore(),
                new SqliteMemoryStore(directory.resolve(testName + ".sqlite")));
    }

    private static MemoryScope scope(
            String team, String user, String agent, String session, String task) {
        return new MemoryScope(team, user, agent, session, task);
    }

    private static MemoryScope withTask(MemoryScope scope, String task) {
        return new MemoryScope(scope.teamId(), scope.userId(), scope.agentId(), scope.sessionId(), task);
    }

    private static CompletedTurn turn(String id, MemoryScope scope, String output) {
        return new CompletedTurn(id, scope, "input", output, List.of("tool"), Instant.now());
    }

    private static AtomicMemory atomic(String id, MemoryScope scope, int version, String content) {
        Instant now = Instant.now();
        return new AtomicMemory(id, scope, MemoryType.FACT, content, 0.8, 6, version, "turn", now, now);
    }

    private static ScenarioMemory scenario(
            String id, MemoryScope scope, int version, String content) {
        return new ScenarioMemory(id, scope, "scenario", content, version, Instant.now());
    }

    private static ProfileMemory profile(
            String id, MemoryScope scope, int version, String content) {
        return new ProfileMemory(id, scope, content, version, Instant.now());
    }

    private static PipelineJob job(
            String id, MemoryScope scope, PipelineJob.Status status, int attempts, Instant updatedAt) {
        return new PipelineJob(id, "turn-" + id, scope, PipelineJob.Stage.L1, status, attempts, "error", updatedAt);
    }
}
