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
 *
 * <p>该服务负责在规划前召回记忆，并在一次 Agent 执行成功后保存完整对话、异步生成
 * 原子记忆、场景记忆和核心画像。服务内部持有执行器，使用完毕后必须调用 {@link #close()}。
 */
public final class MemoryService implements AutoCloseable {

    private final MemoryStore store;
    private final MemoryPipeline pipeline;
    private final HybridMemoryRetriever retriever;
    private final MemoryRecallPolicy recallPolicy;
    private final MemoryContextFormatter formatter = new MemoryContextFormatter();
    private final ExecutorService recallExecutor;

    /**
     * 使用指定的存储、记忆模型、向量模型和召回策略创建记忆服务。
     *
     * @param store L0-L3 记忆及管线任务的存储实现
     * @param model 原子记忆抽取、场景聚合和画像生成模型
     * @param embedding 原子记忆向量化实现
     * @param recallPolicy 召回数量、上下文长度和超时时间策略
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     */
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

    /**
     * 创建仅在当前 JVM 生命周期内保存数据的记忆服务。
     *
     * <p>服务关闭或进程退出后，已记录的记忆将全部丢失。
     *
     * @return 使用默认规则模型、Hashing 向量和召回策略的内存记忆服务
     */
    public static MemoryService inMemory() {
        return create(new InMemoryMemoryStore());
    }

    /**
     * 创建使用本地文件持久化数据的记忆服务。
     *
     * <p>指定目录不存在时会自动创建，记忆数据写入该目录下的
     * {@code memory-state.bin}，应用重启后会重新加载。
     *
     * @param directory 记忆数据目录
     * @return 使用默认规则模型、Hashing 向量和召回策略的持久化记忆服务
     * @throws NullPointerException 当 {@code directory} 为 {@code null} 时抛出
     * @throws IllegalStateException 当目录创建或记忆文件加载失败时抛出
     */
    public static MemoryService persistent(Path directory) {
        return create(new FileMemoryStore(directory));
    }

    /**
     * 创建使用 SQLite 数据库持久化数据的记忆服务。
     *
     * <p>数据库文件及父目录不存在时会自动创建，首次连接会执行内置版本迁移。</p>
     *
     * @param databaseFile SQLite 数据库文件
     * @return 使用默认规则模型、Hashing 向量和召回策略的 SQLite 记忆服务
     */
    public static MemoryService sqlite(Path databaseFile) {
        return create(new SqliteMemoryStore(databaseFile));
    }

    /**
     * 使用默认记忆组件创建服务。
     *
     * @param store 记忆存储实现
     * @return 已完成默认组件装配的记忆服务
     */
    private static MemoryService create(MemoryStore store) {
        return new MemoryService(
                store,
                new RuleBasedMemoryModel(),
                new HashingMemoryEmbedding(),
                MemoryRecallPolicy.defaults());
    }

    /**
     * 规划前按当前作用域和输入召回 L0-L3 记忆。
     *
     * <p>L0 最近对话按会话召回；L1 原子记忆、L2 场景记忆和 L3 核心画像可以在同一
     * team、user、agent 范围内跨会话召回。召回超时或发生异常时不会中断 Agent，
     * 而是返回 {@link MemoryContext#empty(boolean)} 创建的降级空上下文。
     *
     * @param scope 当前 team、user、agent、session 和 task 组成的记忆作用域
     * @param currentInput 当前用户输入，用于相关原子记忆的混合检索
     * @return 可直接提供给规划模型的分层记忆上下文
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
     *
     * <p>方法返回只表示 L0 和管线任务已提交，不表示 L1-L3 已全部生成。需要等待处理完成时，
     * 可调用 {@link #awaitIdle(Duration)}。
     *
     * @param turn 已成功完成的用户输入、Agent 输出及工具输出
     * @throws NullPointerException 当 {@code turn} 为 {@code null} 时抛出
     * @throws RuntimeException 当 L0 或管线任务保存失败时抛出
     */
    public void capture(CompletedTurn turn) {
        pipeline.capture(Objects.requireNonNull(turn, "turn must not be null"));
    }

    /**
     * 手工写入一条 L1 事实，主要用于迁移和管理接口。
     *
     * <p>该方法只写入原子记忆，不会主动触发 L2 场景记忆和 L3 核心画像的重新生成。
     *
     * @param scope 事实所属的记忆作用域
     * @param content 事实内容
     * @throws NullPointerException 当 {@code scope} 或 {@code content} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code content} 不符合原子记忆约束时抛出
     */
    public void rememberFact(MemoryScope scope, String content) {
        store.upsertAtomic(AtomicMemory.create(
                scope, MemoryType.FACT, content, 1.0, 8, "manual:" + Instant.now().toEpochMilli()));
    }

    /**
     * 查询同一会话最近完成的 L0 对话，并按完成时间正序返回。
     *
     * @param scope 查询作用域；team、user、agent、session 必须匹配
     * @param limit 最大返回数量；小于或等于 0 时返回空列表
     * @return 最近完成的对话列表
     */
    public List<CompletedTurn> recentTurns(MemoryScope scope, int limit) {
        return store.listRecentTurns(scope, limit);
    }

    /**
     * 查询当前用户和 Agent 的全部 L1 原子记忆。
     *
     * <p>查询按 team、user、agent 及兼容的 task 过滤，不受 session 限制，因此可跨会话返回。
     *
     * @param scope 查询作用域
     * @return 按更新时间倒序排列的原子记忆列表
     */
    public List<AtomicMemory> atomicMemories(MemoryScope scope) {
        return store.listAtomic(scope);
    }

    /**
     * 查询当前用户和 Agent 的 L2 场景记忆。
     *
     * <p>查询按 team、user、agent 及兼容的 task 过滤，不受 session 限制，因此可跨会话返回。
     *
     * @param scope 查询作用域
     * @return 按更新时间倒序排列的场景记忆列表
     */
    public List<ScenarioMemory> scenarios(MemoryScope scope) {
        return store.listScenarios(scope);
    }

    /**
     * 查询当前用户和 Agent 的 L3 核心画像。
     *
     * <p>画像仅按 team、user、agent 定位，不受 session 和 task 限制。
     *
     * @param scope 查询作用域
     * @return 核心画像；尚未生成时返回 {@code null}
     */
    public ProfileMemory profile(MemoryScope scope) {
        return store.findProfile(scope).orElse(null);
    }

    /**
     * 等待当前已经提交的 L1-L3 管线任务处理完成。
     *
     * @param timeout 最大等待时间
     * @return 全部任务在超时前完成时返回 {@code true}；超时或线程被中断时返回 {@code false}
     */
    public boolean awaitIdle(Duration timeout) {
        return pipeline.awaitIdle(timeout);
    }

    /**
     * 同步完成一次实际召回和上下文格式化。
     *
     * @param scope 当前记忆作用域
     * @param currentInput 当前用户输入
     * @return 未降级的分层记忆上下文
     */
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

    /**
     * 关闭记忆处理管线和召回执行器，释放相关线程资源。
     */
    @Override
    public void close() {
        pipeline.close();
        recallExecutor.close();
    }
}
