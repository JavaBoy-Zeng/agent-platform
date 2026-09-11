package com.github.agentos.kernel;

import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全系统真正的 Agent 执行入口（Runner）。
 *
 * <p>Runner 在执行边界组装 {@link InvocationContext}：查找或创建 {@link Session}、
 * 绑定执行预算 {@link AgentExecutionLimits}、{@link AgentInvocation} 与
 * {@link CancellationToken}，再交给 {@link AgentLoop} 执行。事件携带的状态增量由
 * {@link StateMergingEventPublisher} 统一合并进会话状态。</p>
 *
 * <p>运行时按 {@code sessionId} 保存最新状态；同一会话已有活跃执行时快速拒绝，
 * 不允许取消令牌和 Invocation 元数据相互覆盖。跨会话执行受全局并发许可证保护。
 * 业务循环抛出的运行时异常会被转换为失败状态，避免异常越过运行边界。</p>
 *
 * <p>横切能力经 {@link AgentPluginManager} 接入：执行边界触发生命周期钩子，
 * 领域事件发布时通知插件观察。外部通过 {@link #cancel(String, String)} 请求协作式取消，
 * 令牌随 {@link InvocationContext} 传播到执行链各协作点。</p>
 */
public final class AgentRunner {

    private static final int DEFAULT_MAX_CONCURRENT_RUNS = 128;
    private static final int DEFAULT_MAX_RETAINED_INVOCATIONS = 10_000;

    private final AgentLoop agentLoop;
    private final AgentEventPublisher eventPublisher;
    private final CheckpointStore checkpointStore;
    private final SessionService sessionService;
    private final AgentExecutionLimits budget;
    private final AgentPluginManager plugins;
    private final ArtifactService artifacts;
    private final Semaphore runPermits;
    private final int maxRetainedInvocations;
    private final ConcurrentLinkedQueue<String> completedInvocationIds =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger completedInvocationCount = new AtomicInteger();
    private final ConcurrentMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AgentInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestInvocationIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CancellationToken> activeCancellations =
            new ConcurrentHashMap<>();
    /** 每个会话当前执行线程的引用，cancel 时 interrupt 让阻塞 I/O（shell/HTTP）立即响应取消。 */
    private final ConcurrentMap<String, Thread> activeThreads = new ConcurrentHashMap<>();

    /**
     * 创建 Agent Runner。
     *
     * @param agentLoop 实际执行 Agent 业务逻辑的循环
     * @throws NullPointerException 当执行循环为 {@code null} 时抛出
     */
    public AgentRunner(AgentLoop agentLoop) {
        this(agentLoop, AgentEventPublisher.NOOP);
    }

    /** 创建使用指定领域事件发布器的 Agent Runner。 */
    public AgentRunner(AgentLoop agentLoop, AgentEventPublisher eventPublisher) {
        this(agentLoop, eventPublisher, new InMemoryCheckpointStore());
    }

    /** 创建使用指定领域事件发布器和 CheckpointStore 的 Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore) {
        this(agentLoop, eventPublisher, checkpointStore, new InMemorySessionService());
    }

    /** 创建将事件同时发布给监听器并写入存储的 Agent Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher, AgentEventStore eventStore) {
        this(agentLoop, eventPublisher, eventStore, new InMemoryCheckpointStore());
    }

    /** 创建同时使用 EventStore 与 CheckpointStore 的完整 Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore) {
        this(agentLoop, eventPublisher, eventStore, checkpointStore, new InMemorySessionService());
    }

    /** 创建使用指定会话服务的完整 Runner；事件携带的状态增量会合并进会话状态。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService) {
        this(agentLoop, eventPublisher, eventStore, checkpointStore, sessionService,
                AgentExecutionLimits.defaults());
    }

    /** 创建使用指定会话服务和执行预算的完整 Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService, AgentExecutionLimits budget) {
        this(agentLoop, storingPublisher(eventPublisher, eventStore),
                checkpointStore, sessionService, budget, AgentPluginManager.empty());
    }

    /** 创建带事件落库、会话服务、执行预算与插件的完整 Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService, AgentExecutionLimits budget,
            AgentPluginManager plugins) {
        this(agentLoop, storingPublisher(eventPublisher, eventStore),
                checkpointStore, sessionService, budget, plugins);
    }

    /** 创建带事件落库、会话服务、执行预算、插件与产物存储的完整 Runner。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService, AgentExecutionLimits budget,
            AgentPluginManager plugins, ArtifactService artifacts) {
        this(agentLoop, storingPublisher(eventPublisher, eventStore),
                checkpointStore, sessionService, budget, plugins, artifacts);
    }

    /** 完整构造：额外限制跨会话同时执行的任务数。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService, AgentExecutionLimits budget,
            AgentPluginManager plugins, ArtifactService artifacts,
            int maxConcurrentRuns) {
        this(agentLoop, storingPublisher(eventPublisher, eventStore),
                checkpointStore, sessionService, budget, plugins, artifacts,
                maxConcurrentRuns);
    }

    /** 完整构造：额外配置并发和终态 Invocation 保留上限。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore,
            SessionService sessionService, AgentExecutionLimits budget,
            AgentPluginManager plugins, ArtifactService artifacts,
            int maxConcurrentRuns, int maxRetainedInvocations) {
        this(agentLoop, storingPublisher(eventPublisher, eventStore),
                checkpointStore, sessionService, budget, plugins, artifacts,
                maxConcurrentRuns, maxRetainedInvocations);
    }

    /** 规范构造：事件发布器已就绪（含落库通道），会话服务负责消费状态增量。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService) {
        this(agentLoop, eventPublisher, checkpointStore, sessionService,
                AgentExecutionLimits.defaults());
    }

    /** 规范构造：额外指定本次运行共享的执行预算。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService,
            AgentExecutionLimits budget) {
        this(agentLoop, eventPublisher, checkpointStore, sessionService, budget,
                AgentPluginManager.empty());
    }

    /** 完整构造：额外指定横切能力插件集合。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService,
            AgentExecutionLimits budget, AgentPluginManager plugins) {
        this(agentLoop, eventPublisher, checkpointStore, sessionService, budget,
                plugins, ArtifactService.NOOP);
    }

    /** 最完整构造：横切能力插件集合与产物存储。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService,
            AgentExecutionLimits budget, AgentPluginManager plugins,
            ArtifactService artifacts) {
        this(agentLoop, eventPublisher, checkpointStore, sessionService, budget, plugins,
                artifacts, DEFAULT_MAX_CONCURRENT_RUNS);
    }

    /** 最完整构造：额外限制跨会话同时执行的任务数。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService,
            AgentExecutionLimits budget, AgentPluginManager plugins,
            ArtifactService artifacts, int maxConcurrentRuns) {
        this(agentLoop, eventPublisher, checkpointStore, sessionService, budget, plugins,
                artifacts, maxConcurrentRuns, DEFAULT_MAX_RETAINED_INVOCATIONS);
    }

    /** 最完整构造：额外配置并发和终态 Invocation 保留上限。 */
    public AgentRunner(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore, SessionService sessionService,
            AgentExecutionLimits budget, AgentPluginManager plugins,
            ArtifactService artifacts, int maxConcurrentRuns,
            int maxRetainedInvocations) {
        if (maxConcurrentRuns <= 0) {
            throw new IllegalArgumentException("maxConcurrentRuns must be positive");
        }
        if (maxRetainedInvocations <= 0) {
            throw new IllegalArgumentException("maxRetainedInvocations must be positive");
        }
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop must not be null");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher must not be null");
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore, "checkpointStore must not be null");
        this.sessionService = Objects.requireNonNull(
                sessionService, "sessionService must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.plugins = Objects.requireNonNull(plugins, "plugins must not be null");
        this.artifacts = artifacts == null ? ArtifactService.NOOP : artifacts;
        this.runPermits = new Semaphore(maxConcurrentRuns, true);
        this.maxRetainedInvocations = maxRetainedInvocations;
    }

    private static AgentEventPublisher storingPublisher(
            AgentEventPublisher eventPublisher, AgentEventStore eventStore) {
        return new CompositeAgentEventPublisher(java.util.List.of(
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null"),
                new StoringAgentEventPublisher(Objects.requireNonNull(
                        eventStore, "eventStore must not be null"))));
    }

    /**
     * 在指定上下文中执行一次 Agent 循环并保存最终状态。
     *
     * @param request 本次用户请求
     * @param context 本次身份和任务作用域上下文
     * @return 执行结束后的状态快照
     * @throws NullPointerException 当请求或上下文为 {@code null} 时抛出
     */
    public AgentState run(AgentRequest request, InvocationContext context) {
        return run(request, context, AgentEventSink.NOOP);
    }

    /**
     * 在指定上下文中执行 Agent，并向调用方流式发送阶段事件。
     *
     * @param request 本次用户请求
     * @param context 本次身份和任务上下文
     * @param eventSink 运行事件接收端
     * @return 执行结束后的状态快照
     */
    public AgentState run(
            AgentRequest request, InvocationContext context, AgentEventSink eventSink) {
        return run(request, context, eventSink, AgentEventPublisher.NOOP);
    }

    /**
     * 执行 Agent，并把领域事件单独发送到本次运行发布器。
     *
     * <p>领域事件发布器与本次运行的客户端事件接收端互相独立；上层负责把允许公开的
     * token、工具和审批生命周期统一映射为自己的事件协议。</p>
     */
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            AgentEventPublisher invocationEventPublisher) {
        return runDetailed(request, context, eventSink, invocationEventPublisher).state();
    }

    /** 执行 Agent，并返回与状态绑定的准确 Invocation 标识。 */
    public AgentRunResult runDetailed(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            AgentEventPublisher invocationEventPublisher) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        Objects.requireNonNull(
                invocationEventPublisher, "invocationEventPublisher must not be null");
        try (RunPermit runPermit = acquireRunPermit(request.sessionId())) {
            return runAdmitted(
                    request, context, eventSink, invocationEventPublisher);
        }
    }

    private AgentRunResult runAdmitted(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            AgentEventPublisher invocationEventPublisher) {
        AgentInvocation invocation = new AgentInvocation(
                UUID.randomUUID().toString(), request.sessionId(), context.agentId(),
                context.taskId(), Instant.now());
        invocations.put(invocation.invocationId(), invocation);
        latestInvocationIds.put(request.sessionId(), invocation.invocationId());
        CancellationToken token = CancellationToken.notCancelled();
        AgentEventPublisher runPublisher = new StateMergingEventPublisher(
                new CompositeAgentEventPublisher(java.util.List.of(
                        eventPublisher, invocationEventPublisher, plugins,
                        clientLifecyclePublisher(eventSink), delegatedProgressPublisher(eventSink))),
                sessionService);
        InvocationContext invocationContext = bind(request, context)
                .withRuntime(invocation, runPublisher)
                .withCancellation(token);
        plugins.beforeRun(request, invocationContext);
        activeCancellations.put(request.sessionId(), token);
        activeThreads.put(request.sessionId(), Thread.currentThread());
        AgentState result;
        try {
            result = states.compute(request.sessionId(), (sessionId, previous) -> {
                AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
                invocation.start();
                publish(runPublisher, DefaultAgentEvent.of(
                        invocationContext, AgentEventType.AGENT_STARTED, request.objective(),
                        java.util.Map.of("taskId", context.taskId(), "iteration", running.iteration())));
                AgentEventSink publishingSink = event -> {
                    eventSink.emit(event);
                    mapLegacyEvent(invocationContext, event)
                            .ifPresent(domainEvent -> publish(runPublisher, domainEvent));
                };
                try (var traceScope = ExecutionTrace.open(invocationContext)) {
                    AgentState executed = Objects.requireNonNull(
                            agentLoop.run(request, invocationContext, running, publishingSink),
                            "agentLoop returned null state");
                    if (executed.status() == AgentState.Status.RUNNING) {
                        executed = executed.fail("agent loop finished without a terminal state");
                    }
                    invocation.finish(executed);
                    if (executed.status() == AgentState.Status.WAITING) {
                        saveCheckpoint(invocationContext, request, invocation);
                        persistWaitingState(request.sessionId(), executed);
                    } else {
                        checkpointStore.delete(invocation.invocationId());
                    }
                    publishTerminal(invocationContext, executed, terminalDelta(
                            request, context.userId(), executed));
                    return executed;
                } catch (RuntimeException exception) {
                    String message = exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
                    if (token.isCancelled()
                            || Thread.currentThread().isInterrupted()
                            || exception instanceof java.util.concurrent.CancellationException) {
                        // cancel() 现在同时设置 token 与 interrupt 线程；
                        // token 已取消时优先用 token 的 reason，让取消原因来自
                        // 用户调用 cancel() 时传入的文本，而非 InterruptedException 的 message。
                        String cancelReason = token.reason().orElse(message);
                        AgentState cancelled = running.cancel(cancelReason);
                        invocation.finish(cancelled);
                        publishTerminal(invocationContext, cancelled, terminalDelta(
                                request, context.userId(), cancelled));
                        return cancelled;
                    }
                    plugins.onRunError(request, invocationContext, exception);
                    AgentState failed = running.fail(message);
                    invocation.fail(exception);
                    publishTerminal(invocationContext, failed, terminalDelta(
                            request, context.userId(), failed));
                    return failed;
                }
            });
        } finally {
            activeCancellations.remove(request.sessionId(), token);
            activeThreads.remove(request.sessionId(), Thread.currentThread());
        }
        plugins.afterRun(request, invocationContext, result);
        retainTerminalInvocation(invocation, result);
        return new AgentRunResult(invocation.invocationId(), result);
    }

    /** Runner 在执行边界组装 InvocationContext：注入执行预算、产物存储与当前会话快照。 */
    private InvocationContext bind(AgentRequest request, InvocationContext context) {
        InvocationContext bound = context.withBudget(budget).withArtifacts(artifacts);
        Session session = loadSessionQuietly(request, context.userId());
        return session == null ? bound : bound.withSession(session);
    }

    /** 读取会话快照；会话服务不可用时降级为不注入，不影响运行本身。 */
    private Session loadSessionQuietly(AgentRequest request, String userId) {
        try {
            return sessionService.getOrCreate(request.sessionId(), userId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 查询指定会话最新的运行状态。
     *
     * @param sessionId 会话标识
     * @return 状态存在时返回包含状态的 {@link Optional}，否则返回空值
     */
    public Optional<AgentState> state(String sessionId) {
        AgentState runtimeState = states.get(sessionId);
        if (runtimeState != null) {
            return Optional.of(runtimeState);
        }
        try {
            return sessionService.find(sessionId).map(AgentRunner::restoreState);
        } catch (RuntimeException ignored) {
            // 状态查询的持久化回退不可用时保持原有“未找到”语义。
            return Optional.empty();
        }
    }

    /** 确保会话由当前可信用户创建或持有；归属冲突时返回 false。 */
    public boolean ensureSessionOwner(String sessionId, String userId) {
        try {
            return sessionService.getOrCreate(sessionId, userId).userId().equals(userId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 判断指定会话是否属于当前可信用户。 */
    public boolean ownsSession(String sessionId, String userId) {
        try {
            return sessionService.findByUser(sessionId, userId).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 仅向会话所属用户返回运行状态，避免内存态绕过持久化层归属校验。 */
    public Optional<AgentState> state(String sessionId, String userId) {
        return ownsSession(sessionId, userId) ? state(sessionId) : Optional.empty();
    }

    /** 按 Invocation 标识查询运行记录。 */
    public Optional<AgentInvocation> invocation(String invocationId) {
        return Optional.ofNullable(invocations.get(invocationId));
    }

    /** 查询指定 Session 最近一次运行记录。 */
    public Optional<AgentInvocation> latestInvocation(String sessionId) {
        return Optional.ofNullable(latestInvocationIds.get(sessionId)).flatMap(this::invocation);
    }

    /** 使用原 InvocationId 解决挂起动作并从 Checkpoint 恢复执行。 */
    public AgentState resume(String invocationId, PendingActionResolution resolution) {
        return resume(invocationId, resolution, AgentEventSink.NOOP);
    }

    /** 使用事件接收端恢复指定 Invocation。 */
    public AgentState resume(
            String invocationId, PendingActionResolution resolution, AgentEventSink eventSink) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        AgentCheckpoint checkpoint = checkpointStore.load(invocationId).orElseThrow(
                () -> new IllegalArgumentException("checkpoint not found: " + invocationId));
        try (RunPermit runPermit = acquireRunPermit(checkpoint.sessionId())) {
            return resumeAdmitted(
                    invocationId, resolution, eventSink, checkpoint);
        }
    }

    private AgentState resumeAdmitted(
            String invocationId,
            PendingActionResolution resolution,
            AgentEventSink eventSink,
            AgentCheckpoint checkpoint) {
        PendingAction pending = Objects.requireNonNull(
                checkpoint.pendingAction(), "checkpoint has no pending action");
        if (!pending.pendingActionId().equals(resolution.pendingActionId())) {
            throw new IllegalArgumentException("pending action id does not match checkpoint");
        }
        AgentInvocation invocation = invocations.computeIfAbsent(invocationId, ignored ->
                new AgentInvocation(
                        invocationId, checkpoint.sessionId(), checkpoint.agentId(),
                        checkpoint.taskId(), checkpoint.savedAt()));
        if (invocation.pendingAction() == null) {
            // 进程重启后重建的 Invocation 需要回填挂起动作，否则恢复执行时
            // 已批准的调用会被再次拦截进入新的审批循环。
            invocation.waitFor(pending);
        }
        invocation.resolve(resolution);
        CancellationToken token = CancellationToken.notCancelled();
        AgentEventPublisher resumePublisher = new StateMergingEventPublisher(
                new CompositeAgentEventPublisher(java.util.List.of(
                        eventPublisher,
                        plugins,
                        delegatedProgressPublisher(eventSink),
                        clientLifecyclePublisher(eventSink))),
                sessionService);
        AgentRequest request = AgentRequest.of(checkpoint.sessionId(), checkpoint.objective());
        InvocationContext context = bind(request, new InvocationContext(
                checkpoint.teamId(), checkpoint.userId(), checkpoint.agentId(),
                checkpoint.taskId())).withRuntime(invocation, resumePublisher)
                .withCancellation(token);
        publish(resumePublisher, DefaultAgentEvent.of(
                context, AgentEventType.HUMAN_ACTION_RESOLVED,
                resolution.approved() ? "人工操作已批准" : "人工操作已拒绝",
                java.util.Map.of(
                        "pendingActionId", resolution.pendingActionId(),
                        "approved", resolution.approved())));
        plugins.beforeRun(request, context);
        if (!resolution.approved()) {
            AgentState rejected = states.getOrDefault(
                    checkpoint.sessionId(), AgentState.ready()).fail("human approval rejected");
            states.put(checkpoint.sessionId(), rejected);
            invocation.finish(rejected);
            agentLoop.discard(checkpoint);
            checkpointStore.delete(invocationId);
            publishTerminal(context, rejected, java.util.Map.of(
                    "lastObjective", checkpoint.objective(),
                    "lastStatus", rejected.status().name(),
                    "agentState", stateSnapshot(rejected)));
            plugins.afterRun(request, context, rejected);
            retainTerminalInvocation(invocation, rejected);
            return rejected;
        }
        activeCancellations.put(checkpoint.sessionId(), token);
        activeThreads.put(checkpoint.sessionId(), Thread.currentThread());
        AgentState result;
        try {
            result = states.compute(checkpoint.sessionId(), (sessionId, previous) -> {
                AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
                invocation.start();
                AgentEventSink publishingSink = event -> {
                    eventSink.emit(event);
                    mapLegacyEvent(context, event).ifPresent(this::publish);
                };
                AgentState resumed;
                try (var traceScope = ExecutionTrace.open(context)) {
                    resumed = agentLoop.resume(request, context, running, checkpoint, resolution, publishingSink);
                }
                invocation.finish(resumed);
                if (resumed.status() == AgentState.Status.WAITING) {
                    saveCheckpoint(context, request, invocation);
                    persistWaitingState(checkpoint.sessionId(), resumed);
                } else {
                    checkpointStore.delete(invocationId);
                    publishTerminal(context, resumed, terminalDelta(
                            request, checkpoint.userId(), resumed));
                }
                return resumed;
            });
        } catch (RuntimeException exception) {
            plugins.onRunError(request, context, exception);
            throw exception;
        } finally {
            activeCancellations.remove(checkpoint.sessionId(), token);
            activeThreads.remove(checkpoint.sessionId(), Thread.currentThread());
        }
        plugins.afterRun(request, context, result);
        retainTerminalInvocation(invocation, result);
        return result;
    }

    /**
     * 请求取消指定会话当前正在执行的 Invocation。
     *
     * <p>设置协作式取消令牌后立即返回，不等待执行链退出；执行链在下一个协作点
     * （计划步骤边界、工具调用边界、直答调用边界）感知并收敛为 CANCELLED 终态。
     * 同时中断执行线程，让阻塞 I/O（shell waitFor、HTTP 流式读）立即响应取消，
     * 不必等到下一个协作点。</p>
     *
     * @param sessionId 会话标识
     * @param cancelReason 取消原因
     * @return 会话确有正在执行的 Invocation 时返回 {@code true}
     */
    public boolean cancel(String sessionId, String cancelReason) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        CancellationToken token = activeCancellations.get(sessionId);
        if (token == null) {
            return false;
        }
        token.cancel(cancelReason);
        // 中断执行线程，让阻塞 I/O 立即响应取消。
        Thread thread = activeThreads.get(sessionId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        return true;
    }

    private RunPermit acquireRunPermit(String sessionId) {
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            throw new AgentRunRejectedException(
                    AgentRunRejectedException.Reason.SESSION_BUSY,
                    "session already has an active run: " + sessionId);
        }
        if (!runPermits.tryAcquire()) {
            activeSessions.remove(sessionId, Boolean.TRUE);
            throw new AgentRunRejectedException(
                    AgentRunRejectedException.Reason.CAPACITY_EXCEEDED,
                    "agent run capacity exceeded");
        }
        return new RunPermit(sessionId);
    }

    /** 有界保留终态运行；WAITING 必须保留到人工处理完成。 */
    private void retainTerminalInvocation(
            AgentInvocation invocation, AgentState result) {
        if (result.status() == AgentState.Status.WAITING) {
            return;
        }
        completedInvocationIds.add(invocation.invocationId());
        completedInvocationCount.incrementAndGet();
        while (completedInvocationCount.get() > maxRetainedInvocations) {
            String expiredId = completedInvocationIds.poll();
            if (expiredId == null) {
                return;
            }
            completedInvocationCount.decrementAndGet();
            AgentInvocation expired = invocations.remove(expiredId);
            if (expired != null
                    && latestInvocationIds.remove(expired.sessionId(), expiredId)) {
                states.remove(expired.sessionId());
            }
        }
    }

    private final class RunPermit implements AutoCloseable {
        private final String sessionId;
        private boolean closed;

        private RunPermit(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            runPermits.release();
            activeSessions.remove(sessionId, Boolean.TRUE);
        }
    }

    /** 单次执行的稳定结果，避免调用方通过 latest 查询串到后续运行。 */
    public record AgentRunResult(String invocationId, AgentState state) {
        public AgentRunResult {
            if (invocationId == null || invocationId.isBlank()) {
                throw new IllegalArgumentException("invocationId must not be blank");
            }
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    /** 按 Invocation 标识查询当前 Checkpoint。 */
    public Optional<AgentCheckpoint> checkpoint(String invocationId) {
        return checkpointStore.load(invocationId);
    }

    private void saveCheckpoint(
            InvocationContext context, AgentRequest request, AgentInvocation invocation) {
        PendingAction action = Objects.requireNonNull(
                invocation.pendingAction(), "waiting invocation must have pendingAction");
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                request.sessionId(), invocation.invocationId(), context.agentId(), context.taskId(),
                context.teamId(), context.userId(), request.objective(), "", "", 0,
                java.util.List.of(), java.util.Map.of(), action,
                new ExecutionCounters(
                        invocation.modelCalls(), invocation.toolCalls(), invocation.replans(),
                        invocation.steps()),
                AgentRunStatus.WAITING, Instant.now());
        checkpointStore.save(Objects.requireNonNull(
                agentLoop.checkpoint(request, context, checkpoint),
                "agentLoop returned null checkpoint"));
    }

    private Optional<AgentEvent> mapLegacyEvent(InvocationContext context, AgentRunEvent event) {
        AgentEventType type = switch (event.type()) {
            case PLAN_CREATED -> AgentEventType.PLAN_CREATED;
            case REPLAN -> AgentEventType.REPLAN_STARTED;
            case DECISION, OBSERVATION, USAGE -> AgentEventType.STEP_COMPLETED;
            case ROUTE_DECIDED -> AgentEventType.ROUTE_DECIDED;
            case ROUTE_REJECTED -> AgentEventType.ROUTE_REJECTED;
            case ROUTE_CLARIFICATION_REQUIRED ->
                    AgentEventType.ROUTE_CLARIFICATION_REQUIRED;
            case TOOL_STARTED -> AgentEventType.STEP_STARTED;
            case TOOL_FINISHED -> "COMPLETED".equals(event.data().get("status"))
                    ? AgentEventType.STEP_COMPLETED : AgentEventType.STEP_FAILED;
            default -> null;
        };
        var data = new java.util.LinkedHashMap<>(event.data());
        data.put("runEventType", event.type().name());
        return type == null ? Optional.empty() : Optional.of(
                DefaultAgentEvent.of(context, type, event.message(), data));
    }

    private void publishTerminal(
            InvocationContext context, AgentState state, java.util.Map<String, Object> stateDelta) {
        if (state.status() == AgentState.Status.WAITING) {
            return;
        }
        AgentEventType type = state.status() == AgentState.Status.COMPLETED
                ? AgentEventType.AGENT_COMPLETED : AgentEventType.AGENT_FAILED;
        String message = state.status() == AgentState.Status.COMPLETED ? state.output() : state.error();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", state.status().name());
        // 跨轮 thinking 模式：把思维链写进终态事件，会话历史据此回传 reasoning_content。
        if (state.status() == AgentState.Status.COMPLETED
                && state.reasoning() != null && !state.reasoning().isBlank()) {
            data.put("reasoningContent", state.reasoning());
        }
        publish(context.eventPublisher(), DefaultAgentEvent.of(
                context, type, message, java.util.Map.copyOf(data),
                EventActions.stateDelta(stateDelta)));
    }

    /**
     * 构造一次用户请求轮次的终态增量：记录最近目标、最近状态与累计轮次。
     *
     * <p>轮次以“用户请求完成一个完整轮回”为单位计数，审批恢复不重复计数。
     * 会话服务属于 Runner 的观察面，读取失败时降级为不含轮次的增量。</p>
     */
    private java.util.Map<String, Object> terminalDelta(
            AgentRequest request, String userId, AgentState state) {
        java.util.Map<String, Object> delta = new java.util.LinkedHashMap<>();
        delta.put("lastObjective", request.objective());
        delta.put("lastStatus", state.status().name());
        delta.put("agentState", stateSnapshot(state));
        try {
            long turnCount = sessionService.getOrCreate(request.sessionId(), userId)
                    .state().longValue("turnCount", 0) + 1;
            delta.put("turnCount", turnCount);
        } catch (RuntimeException ignored) {
            // 会话状态不可用不影响运行结果本身。
        }
        return delta;
    }

    /** 持久化完整运行快照，使状态接口在服务重启后仍可恢复响应。 */
    private static java.util.Map<String, Object> stateSnapshot(AgentState state) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("status", state.status().name());
        snapshot.put("iteration", state.iteration());
        snapshot.put("output", state.output());
        snapshot.put("error", state.error());
        snapshot.put("reasoning", state.reasoning());
        snapshot.put("updatedAt", state.updatedAt().toString());
        return java.util.Map.copyOf(snapshot);
    }

    /** WAITING 不发布终态事件，单独保存快照但不增加已完成轮次。 */
    private void persistWaitingState(String sessionId, AgentState state) {
        try {
            sessionService.applyDelta(
                    sessionId, java.util.Map.of("agentState", stateSnapshot(state)));
        } catch (RuntimeException ignored) {
            // 会话存储属于观察面，写入失败不应中断等待审批的运行。
        }
    }

    /** 兼容新完整快照与历史版本仅含 lastStatus/turnCount 的会话记录。 */
    private static AgentState restoreState(Session session) {
        SessionState sessionState = session.state();
        Object stored = sessionState.value("agentState");
        java.util.Map<?, ?> snapshot = stored instanceof java.util.Map<?, ?> map
                ? map : java.util.Map.of();
        AgentState.Status status = parseStatus(textValue(
                snapshot.get("status"), sessionState.stringValue("lastStatus", "READY")));
        int iteration = nonNegativeInt(
                snapshot.get("iteration"), sessionState.longValue("turnCount", 0));
        String output = textValue(snapshot.get("output"), "");
        String error = textValue(snapshot.get("error"), "");
        String reasoning = textValue(snapshot.get("reasoning"), "");
        Instant updatedAt = parseInstant(
                textValue(snapshot.get("updatedAt"), ""), session.lastActiveAt());
        return new AgentState(status, iteration, output, error, reasoning, updatedAt);
    }

    private static AgentState.Status parseStatus(String value) {
        try {
            return AgentState.Status.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return AgentState.Status.READY;
        }
    }

    private static int nonNegativeInt(Object value, long fallback) {
        long parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.longValue();
        } else if (value != null) {
            try {
                parsed = Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, parsed));
    }

    private static String textValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void publish(AgentEvent event) {
        publish(eventPublisher, event);
    }

    /** 子任务领域进度也进入普通运行 SSE；不把子任务终态当作根任务终态。 */
    private static AgentEventPublisher delegatedProgressPublisher(AgentEventSink sink) {
        return event -> {
            if (event.data().containsKey("traceKind")) return;
            if (!event.data().containsKey("subInvocationId")) return;
            Object raw = event.data().get("runEventType");
            if (!(raw instanceof String name)) return;
            AgentRunEvent.Type type;
            try { type = AgentRunEvent.Type.valueOf(name); }
            catch (IllegalArgumentException ignored) { return; }
            if (type != AgentRunEvent.Type.DECISION && type != AgentRunEvent.Type.TOOL_STARTED
                    && type != AgentRunEvent.Type.TOOL_FINISHED) return;
            sink.emit(AgentRunEvent.of(type, event.sessionId(), event.message(), event.data()));
        };
    }

    /**
     * 只把真实工具和审批生命周期转入用户事件流。模型请求、原始响应、系统提示词和
     * reasoning trace 永远不经过该边界。
     */
    private static AgentEventPublisher clientLifecyclePublisher(AgentEventSink sink) {
        return event -> {
            AgentRunEvent.Type type = switch (event.type()) {
                case TOOL_CALL_STARTED -> AgentRunEvent.Type.TOOL_STARTED;
                case TOOL_CALL_COMPLETED, TOOL_CALL_FAILED -> AgentRunEvent.Type.TOOL_FINISHED;
                case HUMAN_ACTION_REQUIRED, HUMAN_ACTION_RESOLVED -> AgentRunEvent.Type.DECISION;
                default -> null;
            };
            if (type == null || event.data().containsKey("traceKind")) return;
            var data = new java.util.LinkedHashMap<>(event.data());
            data.putIfAbsent("agentId", event.agentId());
            data.putIfAbsent("invocationId", event.invocationId());
            data.putIfAbsent("domainEventType", event.type().name());
            sink.emit(AgentRunEvent.of(type, event.sessionId(), event.message(), data));
        };
    }

    private static void publish(AgentEventPublisher publisher, AgentEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ignored) {
            // 领域事件观察端不得破坏 Runner 执行。
        }
    }
}
