package com.github.agentos.kernel;

import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 面向会话的 Agent 运行入口。
 *
 * <p>运行时按 {@code sessionId} 保存最新状态，并通过并发 Map 的原子计算保证同一会话的
 * 状态更新串行执行。业务循环抛出的运行时异常会被转换为失败状态，避免异常越过运行边界。</p>
 */
public final class AgentRuntime {

    private final AgentLoop agentLoop;
    private final AgentEventPublisher eventPublisher;
    private final CheckpointStore checkpointStore;
    private final ConcurrentMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AgentInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestInvocationIds = new ConcurrentHashMap<>();

    /**
     * 创建 Agent 运行时。
     *
     * @param agentLoop 实际执行 Agent 业务逻辑的循环
     * @throws NullPointerException 当执行循环为 {@code null} 时抛出
     */
    public AgentRuntime(AgentLoop agentLoop) {
        this(agentLoop, AgentEventPublisher.NOOP);
    }

    /** 创建使用指定领域事件发布器的 Agent 运行时。 */
    public AgentRuntime(AgentLoop agentLoop, AgentEventPublisher eventPublisher) {
        this(agentLoop, eventPublisher, new InMemoryCheckpointStore());
    }

    /** 创建使用指定领域事件发布器和 CheckpointStore 的运行时。 */
    public AgentRuntime(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            CheckpointStore checkpointStore) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop must not be null");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher must not be null");
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore, "checkpointStore must not be null");
    }

    /** 创建将事件同时发布给监听器并写入存储的 Agent 运行时。 */
    public AgentRuntime(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher, AgentEventStore eventStore) {
        this(agentLoop, new CompositeAgentEventPublisher(java.util.List.of(
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null"),
                new StoringAgentEventPublisher(eventStore))), new InMemoryCheckpointStore());
    }

    /** 创建同时使用 EventStore 与 CheckpointStore 的完整运行时。 */
    public AgentRuntime(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher,
            AgentEventStore eventStore, CheckpointStore checkpointStore) {
        this(agentLoop, new CompositeAgentEventPublisher(java.util.List.of(
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null"),
                new StoringAgentEventPublisher(eventStore))), checkpointStore);
    }

    /**
     * 在指定上下文中执行一次 Agent 循环并保存最终状态。
     *
     * @param request 本次用户请求
     * @param context 本次身份和任务上下文
     * @return 执行结束后的状态快照
     * @throws NullPointerException 当请求或上下文为 {@code null} 时抛出
     */
    public AgentState run(AgentRequest request, AgentContext context) {
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
            AgentRequest request, AgentContext context, AgentEventSink eventSink) {
        return run(request, context, eventSink, AgentEventPublisher.NOOP);
    }

    /**
     * 执行 Agent，并把领域事件单独发送到本次运行发布器。
     *
     * <p>领域事件发布器与旧版流事件接收端互相独立，便于 SSE 区分 token、兼容事件和
     * Runtime 领域事件。</p>
     */
    public AgentState run(
            AgentRequest request,
            AgentContext context,
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
        AgentEventPublisher runPublisher = new CompositeAgentEventPublisher(java.util.List.of(
                eventPublisher, invocationEventPublisher));
        AgentContext invocationContext = context.withRuntime(invocation, runPublisher);
        return states.compute(request.sessionId(), (sessionId, previous) -> {
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
                AgentState result = Objects.requireNonNull(
                        agentLoop.run(request, invocationContext, running, publishingSink),
                        "agentLoop returned null state");
                if (result.status() == AgentState.Status.RUNNING) {
                    result = result.fail("agent loop finished without a terminal state");
                }
                invocation.finish(result);
                if (result.status() == AgentState.Status.WAITING) {
                    saveCheckpoint(invocationContext, request, invocation);
                } else {
                    checkpointStore.delete(invocation.invocationId());
                }
                publishTerminal(invocationContext, result);
                return result;
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                if (Thread.currentThread().isInterrupted()
                        || exception instanceof java.util.concurrent.CancellationException) {
                    AgentState cancelled = running.cancel(message);
                    invocation.finish(cancelled);
                    publishTerminal(invocationContext, cancelled);
                    return cancelled;
                }
                AgentState failed = running.fail(message);
                invocation.fail(exception);
                publishTerminal(invocationContext, failed);
                return failed;
            }
        });
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
        invocation.resolve(resolution);
        AgentContext context = new AgentContext(
                checkpoint.teamId(), checkpoint.userId(), checkpoint.agentId(),
                checkpoint.taskId()).withRuntime(invocation, eventPublisher);
        publish(DefaultAgentEvent.of(
                context, AgentEventType.HUMAN_ACTION_RESOLVED,
                resolution.approved() ? "人工操作已批准" : "人工操作已拒绝",
                java.util.Map.of(
                        "pendingActionId", resolution.pendingActionId(),
                        "approved", resolution.approved())));
        if (!resolution.approved()) {
            AgentState rejected = states.getOrDefault(
                    checkpoint.sessionId(), AgentState.ready()).fail("human approval rejected");
            states.put(checkpoint.sessionId(), rejected);
            invocation.finish(rejected);
            checkpointStore.delete(invocationId);
            publishTerminal(context, rejected);
            return rejected;
        }
        AgentRequest request = AgentRequest.of(checkpoint.sessionId(), checkpoint.objective());
        return states.compute(checkpoint.sessionId(), (sessionId, previous) -> {
            AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
            invocation.start();
            AgentEventSink publishingSink = event -> {
                eventSink.emit(event);
                mapLegacyEvent(context, event).ifPresent(this::publish);
            };
            AgentState result = agentLoop.resume(
                    request, context, running, checkpoint, resolution, publishingSink);
            invocation.finish(result);
            if (result.status() == AgentState.Status.WAITING) {
                saveCheckpoint(context, request, invocation);
            } else {
                checkpointStore.delete(invocationId);
                publishTerminal(context, result);
            }
            return result;
        });
    }

    /** 按 Invocation 标识查询当前 Checkpoint。 */
    public Optional<AgentCheckpoint> checkpoint(String invocationId) {
        return checkpointStore.load(invocationId);
    }

    private void saveCheckpoint(
            AgentContext context, AgentRequest request, AgentInvocation invocation) {
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

    private Optional<AgentEvent> mapLegacyEvent(AgentContext context, AgentRunEvent event) {
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

    private void publishTerminal(AgentContext context, AgentState state) {
        if (state.status() == AgentState.Status.WAITING) {
            return;
        }
        AgentEventType type = state.status() == AgentState.Status.COMPLETED
                ? AgentEventType.AGENT_COMPLETED : AgentEventType.AGENT_FAILED;
        String message = state.status() == AgentState.Status.COMPLETED ? state.output() : state.error();
        publish(context.eventPublisher(), DefaultAgentEvent.of(
                context, type, message, java.util.Map.of("status", state.status().name())));
    }

    private void publish(AgentEvent event) {
        publish(eventPublisher, event);
    }

    private static void publish(AgentEventPublisher publisher, AgentEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ignored) {
            // 领域事件观察端不得破坏 Runtime 执行。
        }
    }
}
