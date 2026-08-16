package com.github.agentos.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPipelineRecoveryTest {

    @Test
    void retriesTransientModelFailureAndCompletesAllStages() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        RuleBasedMemoryModel delegate = new RuleBasedMemoryModel();
        MemoryModel model = new MemoryModel() {
            @Override
            public List<AtomicCandidate> extractAtomic(CompletedTurn turn) {
                if (calls.getAndIncrement() == 0) throw new MemoryAdapterException("temporary failure");
                return delegate.extractAtomic(turn);
            }

            @Override
            public String synthesizeScenario(MemoryScope scope, List<AtomicMemory> memories) {
                return delegate.synthesizeScenario(scope, memories);
            }

            @Override
            public String synthesizeProfile(
                    MemoryScope scope, List<AtomicMemory> memories, List<ScenarioMemory> scenarios) {
                return delegate.synthesizeProfile(scope, memories, scenarios);
            }
        };
        MemoryScope scope = scope();
        CompletedTurn turn = CompletedTurn.success(
                scope, "I prefer Java.", "Preference noted.", List.of());

        try (MemoryPipeline pipeline = new MemoryPipeline(store, model)) {
            pipeline.capture(turn);
            assertThat(pipeline.awaitIdle(Duration.ofSeconds(5))).isTrue();
        }

        PipelineJob job = store.findJob("pipeline:" + turn.id()).orElseThrow();
        assertThat(job.status()).isEqualTo(PipelineJob.Status.COMPLETED);
        assertThat(job.attempts()).isGreaterThanOrEqualTo(4);
        assertThat(store.listAtomic(scope)).isNotEmpty();
    }

    @Test
    void resumesJobLeftRunningByPreviousProcess() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryScope scope = scope();
        CompletedTurn turn = CompletedTurn.success(
                scope, "We must keep tests deterministic.", "Constraint noted.", List.of());
        store.saveTurn(turn);
        store.saveJob(PipelineJob.pending(turn.id(), scope).running());

        try (MemoryPipeline pipeline = new MemoryPipeline(store, new RuleBasedMemoryModel())) {
            assertThat(pipeline.awaitIdle(Duration.ofSeconds(3))).isTrue();
        }

        assertThat(store.findJob("pipeline:" + turn.id()).orElseThrow().status())
                .isEqualTo(PipelineJob.Status.COMPLETED);
        assertThat(store.listAtomic(scope)).isNotEmpty();
    }

    @Test
    void stopsRetryingAfterConfiguredAttemptLimitAndKeepsFailure() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        MemoryModel failing = new MemoryModel() {
            @Override
            public List<AtomicCandidate> extractAtomic(CompletedTurn turn) {
                calls.incrementAndGet();
                throw new MemoryAdapterException("persistent failure");
            }

            @Override
            public String synthesizeScenario(MemoryScope scope, List<AtomicMemory> memories) {
                throw new AssertionError("L2 must not run after L1 failure");
            }

            @Override
            public String synthesizeProfile(
                    MemoryScope scope, List<AtomicMemory> memories, List<ScenarioMemory> scenarios) {
                throw new AssertionError("L3 must not run after L1 failure");
            }
        };
        CompletedTurn turn = CompletedTurn.success(scope(), "input", "output", List.of());

        try (MemoryPipeline pipeline = new MemoryPipeline(store, failing, 3, 1, 2)) {
            pipeline.capture(turn);
            assertThat(pipeline.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }

        PipelineJob job = store.findJob("pipeline:" + turn.id()).orElseThrow();
        assertThat(job.status()).isEqualTo(PipelineJob.Status.FAILED);
        assertThat(job.attempts()).isEqualTo(3);
        assertThat(job.error()).contains("persistent failure");
        assertThat(calls).hasValue(3);
        assertThat(store.listRecoverableJobs(3)).isEmpty();
    }

    private static MemoryScope scope() {
        return new MemoryScope("team", "user", "agent", "session", "task");
    }
}
