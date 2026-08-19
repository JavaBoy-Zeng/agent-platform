package com.github.agentos.kernel;

import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 全系统真正的 Agent 执行入口（Runner）。
 *
 * <p>Runner 在执行边界组装 {@link InvocationContext}：查找或创建 {@link Session}、
 * 绑定执行预算 {@link AgentExecutionLimits}、{@link AgentInvocation} 与
 * {@link CancellationToken}，再交给 {@link AgentLoop} 执行。事件携带的状态增量由
 * {@link StateMergingEventPublisher} 统一合并进会话状态。</p>
 *
 * <p>运行时按 {@code sessionId} 保存最新状态，并通过并发 Map 的原子计算保证同一会话的
 * 状态更新串行执行。业务循环抛出的运行时异常会被转换为失败状态，避免异常越过运行边界。</p>
 *
 * <p>横切能力经 {@link AgentPluginManager} 接入：执行边界触发生命周期钩子，
 * 领域事件发布时通知插件观察。外部通过 {@link #cancel(String, String)} 请求协作式取消，
 * 令牌随 {@link InvocationContext} 传播到执行链各协作点。</p>
 */
public final class AgentRunner {

    private final AgentLoop agentLoop;
    private final AgentEventPublisher eventPublisher;
    private final CheckpointStore checkpointStore;
    private final SessionService sessionService;
    private final AgentExecutionLimits budget;
    private final AgentPluginManager plugins;
    private final ConcurrentMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AgentInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestInvocationIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CancellationToken> activeCancellations =
            new ConcurrentHashMap<>();

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
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop must not be null");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher must not be null");
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore, "checkpointStore must not be null");
        this.sessionService = Objects.requireNonNull(
                sessionService, "sessionService must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.plugins = Objects.requireNonNull(plugins, "plugins must not be null");
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
     * <p>领域事件发布器与旧版流事件接收端互相独立，便于 SSE 区分 token、兼容事件和
     * Runner 领域事件。</p>
     */
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            AgentEventPublisher invocationEventPublisher) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        Objects.requireNonNull(
                invocationEventPublisher, "invocationEventPublisher must not be null");
        AgentInvocation invocation = new AgentInvocation(
                UUID.randomUUID().toString(), request.sessionId(), context.agentId(),
                context.taskId(), Instant.now());
        invocations.put(invocation.invocationId(), invocation);
        latestInvocationIds.put(request.sessionId(), invocation.invocationId());
        CancellationToken token = CancellationToken.notCancelled();
        AgentEventPublisher runPublisher = new StateMergingEventPublisher(
                new CompositeAgentEventPublisher(java.util.List.of(
                        eventPublisher, invocationEventPublisher, plugins)),
                sessionService);
        InvocationContext invocationContext = bind(request, context)
                .withRuntime(invocation, runPublisher)
                .withCancellation(token);
        plugins.beforeRun(request, invocationContext);
        activeCancellations.put(request.sessionId(), token);
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
                try {
                    AgentState executed = Objects.requireNonNull(
                            agentLoop.run(request, invocationContext, running, publishingSink),
                            "agentLoop returned null state");
                    if (executed.status() == AgentState.Status.RUNNING) {
                        executed = executed.fail("agent loop finished without a terminal state");
                    }
                    invocation.finish(executed);
                    if (executed.status() == AgentState.Status.WAITING) {
                        saveCheckpoint(invocationContext, request, invocation);
                    } else {
                        checkpointStore.delete(invocation.invocationId());
                    }
                    publishTerminal(invocationContext, executed, terminalDelta(
                            request, context.userId(), executed.status()));
                    return executed;
                } catch (RuntimeException exception) {
                    String message = exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
                    if (token.isCancelled()
                            || Thread.currentThread().isInterrupted()
                            || exception instanceof java.util.concurrent.CancellationException) {
                        AgentState cancelled = running.cancel(message);
                        invocation.finish(cancelled);
                        publishTerminal(invocationContext, cancelled, terminalDelta(
                                request, context.userId(), cancelled.status()));
                        return cancelled;
                    }
                    plugins.onRunError(request, invocationContext, exception);
                    AgentState failed = running.fail(message);
                    invocation.fail(exception);
                    publishTerminal(invocationContext, failed, terminalDelta(
                            request, context.userId(), failed.status()));
                    return failed;
                }
            });
        } finally {
            activeCancellations.remove(request.sessionId(), token);
        }
        plugins.afterRun(request, invocationContext, result);
        return result;
    }

    /** Runner 在执行边界组装 InvocationContext：注入执行预算与当前会话快照。 */
    private InvocationContext bind(AgentRequest request, InvocationContext context) {
        InvocationContext bound = context.withBudget(budget);
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
        return Optional.ofNullable(states.get(sessionId));
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
                new CompositeAgentEventPublisher(java.util.List.of(eventPublisher, plugins)),
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
                    "lastStatus", rejected.status().name()));
            plugins.afterRun(request, context, rejected);
            return rejected;
        }
        activeCancellations.put(checkpoint.sessionId(), token);
        AgentState result;
        try {
            result = states.compute(checkpoint.sessionId(), (sessionId, previous) -> {
                AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
                invocation.start();
                AgentEventSink publishingSink = event -> {
                    eventSink.emit(event);
                    mapLegacyEvent(context, event).ifPresent(this::publish);
                };
                AgentState resumed = agentLoop.resume(
                        request, context, running, checkpoint, resolution, publishingSink);
                invocation.finish(resumed);
                if (resumed.status() == AgentState.Status.WAITING) {
                    saveCheckpoint(context, request, invocation);
                } else {
                    checkpointStore.delete(invocationId);
                    publishTerminal(context, resumed, terminalDelta(
                            request, checkpoint.userId(), resumed.status()));
                }
                return resumed;
            });
        } catch (RuntimeException exception) {
            plugins.onRunError(request, context, exception);
            throw exception;
        } finally {
            activeCancellations.remove(checkpoint.sessionId(), token);
        }
        plugins.afterRun(request, context, result);
        return result;
    }

    /**
     * 请求取消指定会话当前正在执行的 Invocation。
     *
     * <p>设置协作式取消令牌后立即返回，不等待执行链退出；执行链在下一个协作点
     * （计划步骤边界、工具调用边界、直答调用边界）感知并收敛为 CANCELLED 终态。
     * 阻塞在 I/O 上的执行仍需调用方配合线程中断。</p>
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
        return true;
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
            case TOOL_STARTED -> AgentEventType.STEP_STARTED;
            case TOOL_FINISHED -> "COMPLETED".equals(event.data().get("status"))
                    ? AgentEventType.STEP_COMPLETED : AgentEventType.STEP_FAILED;
            default -> null;
        };
        return type == null ? Optional.empty() : Optional.of(
                DefaultAgentEvent.of(context, type, event.message(), event.data()));
    }

    private void publishTerminal(
            InvocationContext context, AgentState state, java.util.Map<String, Object> stateDelta) {
        if (state.status() == AgentState.Status.WAITING) {
            return;
        }
        AgentEventType type = state.status() == AgentState.Status.COMPLETED
                ? AgentEventType.AGENT_COMPLETED : AgentEventType.AGENT_FAILED;
        String message = state.status() == AgentState.Status.COMPLETED ? state.output() : state.error();
        publish(context.eventPublisher(), DefaultAgentEvent.of(
                context, type, message, java.util.Map.of("status", state.status().name()),
                EventActions.stateDelta(stateDelta)));
    }

    /**
     * 构造一次用户请求轮次的终态增量：记录最近目标、最近状态与累计轮次。
     *
     * <p>轮次以“用户请求完成一个完整轮回”为单位计数，审批恢复不重复计数。
     * 会话服务属于 Runner 的观察面，读取失败时降级为不含轮次的增量。</p>
     */
    private java.util.Map<String, Object> terminalDelta(
            AgentRequest request, String userId, AgentState.Status status) {
        java.util.Map<String, Object> delta = new java.util.LinkedHashMap<>();
        delta.put("lastObjective", request.objective());
        delta.put("lastStatus", status.name());
        try {
            long turnCount = sessionService.getOrCreate(request.sessionId(), userId)
                    .state().longValue("turnCount", 0) + 1;
            delta.put("turnCount", turnCount);
        } catch (RuntimeException ignored) {
            // 会话状态不可用不影响运行结果本身。
        }
        return delta;
    }

    private void publish(AgentEvent event) {
        publish(eventPublisher, event);
    }

    private static void publish(AgentEventPublisher publisher, AgentEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ignored) {
            // 领域事件观察端不得破坏 Runner 执行。
        }
    }
}
