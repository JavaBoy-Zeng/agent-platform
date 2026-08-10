package com.github.agentos.memory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agent 访问 Java 原生 L0-L3 记忆的统一门面。
 */
public final class MemoryService implements AutoCloseable {

    private final MemoryStore store;
    private final MemoryPipeline pipeline;
    private final HybridMemoryRetriever retriever;
    private final MemoryRecallPolicy recallPolicy;
    private final MemoryContextFormatter formatter = new MemoryContextFormatter();
    private final ExecutorService recallExecutor;

    public MemoryService(
            MemoryStore store,
            MemoryModel model,
            MemoryEmbedding embedding,
            MemoryRecallPolicy recallPolicy) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.recallPolicy = Objects.requireNonNull(recallPolicy, "recallPolicy must not be null");
        this.retriever = new HybridMemoryRetriever(store, embedding);
        this.pipeline = new MemoryPipeline(store, model);
        this.recallExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public static MemoryService inMemory() {
        return create(new InMemoryMemoryStore());
    }

    public static MemoryService persistent(Path directory) {
        return create(new FileMemoryStore(directory));
    }

    private static MemoryService create(MemoryStore store) {
        return new MemoryService(
                store,
                new RuleBasedMemoryModel(),
                new HashingMemoryEmbedding(),
                MemoryRecallPolicy.defaults());
    }

    /**
     * 规划前按当前输入召回以前的 L1/L2/L3 记忆，超时或失败时降级为空上下文。
     */
    public MemoryContext recall(MemoryScope scope, String currentInput) {
        CompletableFuture<MemoryContext> task = CompletableFuture.supplyAsync(
                () -> recallNow(scope, currentInput), recallExecutor);
        try {
            return task.get(recallPolicy.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            task.cancel(true);
            return MemoryContext.empty(true);
        }
    }

    /**
     * 成功后先保存完整 L0，再异步启动 L1-L3 管线。
     */
    public void capture(CompletedTurn turn) {
        pipeline.capture(Objects.requireNonNull(turn, "turn must not be null"));
    }

    /**
     * 手工写入一条 L1 事实，主要用于迁移和管理接口。
     */
    public void rememberFact(MemoryScope scope, String content) {
        store.upsertAtomic(AtomicMemory.create(
                scope, MemoryType.FACT, content, 1.0, 8, "manual:" + Instant.now().toEpochMilli()));
    }

    public List<CompletedTurn> recentTurns(MemoryScope scope, int limit) {
        return store.listRecentTurns(scope, limit);
    }

    public List<AtomicMemory> atomicMemories(MemoryScope scope) {
        return store.listAtomic(scope);
    }

    public List<ScenarioMemory> scenarios(MemoryScope scope) {
        return store.listScenarios(scope);
    }

    public ProfileMemory profile(MemoryScope scope) {
        return store.findProfile(scope).orElse(null);
    }

    public boolean awaitIdle(Duration timeout) {
        return pipeline.awaitIdle(timeout);
    }

    private MemoryContext recallNow(MemoryScope scope, String currentInput) {
        List<CompletedTurn> recentTurns = store.listRecentTurns(scope, recallPolicy.maxRecentTurns());
        List<MemorySearchHit> atomic = retriever.search(
                MemoryQuery.planning(scope, currentInput, recallPolicy.maxAtomicResults()));
        List<ScenarioMemory> scenarios = store.listScenarios(scope).stream()
                .limit(recallPolicy.maxScenarios())
                .toList();
        ProfileMemory profile = store.findProfile(scope).orElse(null);
        String formatted = formatter.format(recentTurns, atomic, scenarios, profile, recallPolicy);
        return new MemoryContext(recentTurns, atomic, scenarios, profile, formatted, false);
    }

    @Override
    public void close() {
        pipeline.close();
        recallExecutor.close();
    }
}
