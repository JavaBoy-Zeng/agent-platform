package com.github.agentos.memory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** 按 L1、L2、L3 顺序执行并持久化进度的后台记忆管线。 */
public final class MemoryPipeline implements AutoCloseable {

    private static final int MAX_ATTEMPTS = 6;
    private final MemoryStore store;
    private final MemoryModel model;
    private final ScheduledExecutorService executor;
    private final Map<String, Boolean> scheduled = new ConcurrentHashMap<>();

    public MemoryPipeline(MemoryStore store, MemoryModel model) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agentos-memory-pipeline");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
        recover();
    }

    /** 先幂等保存 L0，再创建可恢复的 Pipeline Job。 */
    public void capture(CompletedTurn turn) {
        store.saveTurn(turn);
        String jobId = "pipeline:" + turn.id();
        PipelineJob existing = store.findJob(jobId).orElse(null);
        if (existing != null && existing.status() == PipelineJob.Status.COMPLETED) return;
        PipelineJob job = existing == null ? PipelineJob.pending(turn.id(), turn.scope()) : existing.retry();
        store.saveJob(job);
        schedule(job.id(), 0);
    }

    /** 等待当前已提交任务结束，主要用于测试、关闭和运维检查。 */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean pending = store.listRecoverableJobs(MAX_ATTEMPTS).stream()
                    .anyMatch(job -> job.status() == PipelineJob.Status.PENDING
                            || job.status() == PipelineJob.Status.RUNNING);
            if (!pending && scheduled.isEmpty()) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void recover() {
        for (PipelineJob job : store.listRecoverableJobs(MAX_ATTEMPTS)) {
            store.saveJob(job.retry());
            schedule(job.id(), 0);
        }
    }

    private void schedule(String jobId, long delayMillis) {
        if (scheduled.putIfAbsent(jobId, Boolean.TRUE) != null) return;
        executor.schedule(() -> {
            PipelineJob failed = null;
            try {
                process(jobId);
            } catch (RuntimeException exception) {
                PipelineJob current = store.findJob(jobId).orElseThrow();
                failed = current.failed(exception);
                store.saveJob(failed);
            } finally {
                scheduled.remove(jobId);
            }
            if (failed != null && failed.attempts() < MAX_ATTEMPTS) {
                PipelineJob retry = failed.retry();
                store.saveJob(retry);
                schedule(retry.id(), Math.min(5_000, 100L << Math.min(5, retry.attempts())));
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void process(String jobId) {
        PipelineJob job = store.findJob(jobId).orElseThrow();
        while (job.stage() != PipelineJob.Stage.DONE) {
            PipelineJob running = job.running();
            store.saveJob(running);
            switch (running.stage()) {
                case L1 -> processL1(running);
                case L2 -> processL2(running);
                case L3 -> processL3(running);
                case DONE -> {
                    return;
                }
            }
            job = running.advance();
            store.saveJob(job);
        }
    }

    private void processL1(PipelineJob job) {
        CompletedTurn turn = store.findTurn(job.turnId()).orElseThrow();
        List<AtomicMemory> existing = store.listAtomic(job.scope());
        for (MemoryModel.AtomicCandidate candidate : model.extractAtomic(turn)) {
            AtomicMemory exact = existing.stream()
                    .filter(memory -> memory.type() == candidate.type())
                    .filter(memory -> TextAnalyzer.normalize(memory.content())
                            .equals(TextAnalyzer.normalize(candidate.content())))
                    .findFirst()
                    .orElse(null);
            if (exact != null) continue;

            AtomicMemory similar = existing.stream()
                    .filter(memory -> memory.type() == candidate.type())
                    .max(Comparator.comparingDouble(memory -> TextAnalyzer.jaccard(
                            memory.content(), candidate.content())))
                    .filter(memory -> TextAnalyzer.jaccard(memory.content(), candidate.content()) >= 0.72)
                    .orElse(null);
            AtomicMemory saved;
            if (similar == null) {
                saved = AtomicMemory.create(
                        job.scope(), candidate.type(), candidate.content(), candidate.confidence(),
                        candidate.priority(), job.turnId());
            } else {
                String content = candidate.content().length() >= similar.content().length()
                        ? candidate.content() : similar.content();
                saved = similar.revise(
                        content,
                        Math.max(similar.confidence(), candidate.confidence()),
                        Math.max(similar.priority(), candidate.priority()),
                        job.turnId());
            }
            store.upsertAtomic(saved);
            existing = store.listAtomic(job.scope());
        }
    }

    private void processL2(PipelineJob job) {
        List<AtomicMemory> memories = store.listAtomic(job.scope());
        String content = model.synthesizeScenario(job.scope(), memories);
        if (content.isBlank()) return;
        String name = job.scope().taskId().isEmpty()
                ? "agent:" + job.scope().agentId()
                : "task:" + job.scope().taskId();
        ScenarioMemory existing = store.listScenarios(job.scope()).stream()
                .filter(scenario -> scenario.name().equals(name))
                .findFirst()
                .orElse(null);
        store.saveScenario(existing == null
                ? ScenarioMemory.create(job.scope(), name, content)
                : existing.revise(content));
    }

    private void processL3(PipelineJob job) {
        List<AtomicMemory> memories = store.listAtomic(job.scope());
        List<ScenarioMemory> scenarios = store.listScenarios(job.scope());
        String content = model.synthesizeProfile(job.scope(), memories, scenarios);
        if (content.isBlank()) return;
        ProfileMemory existing = store.findProfile(job.scope()).orElse(null);
        store.saveProfile(existing == null
                ? ProfileMemory.create(job.scope(), content)
                : existing.revise(content));
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
