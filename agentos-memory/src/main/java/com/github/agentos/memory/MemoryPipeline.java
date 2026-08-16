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

/** 按 L1、L2、L3 顺序执行并持久化进度的后台记忆流水线。 */
public final class MemoryPipeline implements AutoCloseable {

    private static final int DEFAULT_MAX_ATTEMPTS = 6;
    private static final long DEFAULT_BASE_RETRY_DELAY_MILLIS = 100;
    private static final long DEFAULT_MAX_RETRY_DELAY_MILLIS = 5_000;
    private final MemoryStore store;
    private final MemoryModel model;
    private final int maxAttempts;
    private final long baseRetryDelayMillis;
    private final long maxRetryDelayMillis;
    private final ScheduledExecutorService executor;
    private final Map<String, Boolean> scheduled = new ConcurrentHashMap<>();

    /**
     * 使用生产默认重试策略创建记忆流水线。
     *
     * @param store L0-L3 记忆和任务状态存储
     * @param model L1-L3 记忆加工模型
     */
    public MemoryPipeline(MemoryStore store, MemoryModel model) {
        this(store, model, DEFAULT_MAX_ATTEMPTS,
                DEFAULT_BASE_RETRY_DELAY_MILLIS, DEFAULT_MAX_RETRY_DELAY_MILLIS);
    }

    MemoryPipeline(
            MemoryStore store,
            MemoryModel model,
            int maxAttempts,
            long baseRetryDelayMillis,
            long maxRetryDelayMillis) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        if (baseRetryDelayMillis < 0) {
            throw new IllegalArgumentException("baseRetryDelayMillis must not be negative");
        }
        if (maxRetryDelayMillis < baseRetryDelayMillis) {
            throw new IllegalArgumentException(
                    "maxRetryDelayMillis must not be less than baseRetryDelayMillis");
        }
        this.maxAttempts = maxAttempts;
        this.baseRetryDelayMillis = baseRetryDelayMillis;
        this.maxRetryDelayMillis = maxRetryDelayMillis;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agentos-memory-pipeline");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
        recover();
    }

    /**
     * 先幂等保存 L0，再创建可恢复的 Pipeline Job。
     *
     * @param turn 已成功完成的对话轮次
     */
    public void capture(CompletedTurn turn) {
        store.saveTurn(turn);
        String jobId = "pipeline:" + turn.id();
        PipelineJob existing = store.findJob(jobId).orElse(null);
        if (existing != null && existing.status() == PipelineJob.Status.COMPLETED) return;
        PipelineJob job = existing == null ? PipelineJob.pending(turn.id(), turn.scope()) : existing.retry();
        store.saveJob(job);
        schedule(job.id(), 0);
    }

    /**
     * 等待当前已提交任务结束，主要用于测试、关闭和运维检查。
     *
     * @param timeout 最大等待时间
     * @return 所有任务完成或耗尽重试时返回 {@code true}；超时或中断时返回 {@code false}
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean pending = !store.listRecoverableJobs(maxAttempts).isEmpty();
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
        for (PipelineJob job : store.listRecoverableJobs(maxAttempts)) {
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
            if (failed != null && failed.attempts() < maxAttempts) {
                PipelineJob retry = failed.retry();
                store.saveJob(retry);
                long multiplier = 1L << Math.min(20, retry.attempts());
                schedule(retry.id(), Math.min(maxRetryDelayMillis, baseRetryDelayMillis * multiplier));
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
