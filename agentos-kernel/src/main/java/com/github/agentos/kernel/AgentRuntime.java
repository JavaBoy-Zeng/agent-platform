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
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop must not be null");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "eventPublisher must not be null");
    }

    /** 创建将事件同时发布给监听器并写入存储的 Agent 运行时。 */
    public AgentRuntime(
            AgentLoop agentLoop, AgentEventPublisher eventPublisher, AgentEventStore eventStore) {
        this(agentLoop, new CompositeAgentEventPublisher(java.util.List.of(
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null"),
                new StoringAgentEventPublisher(eventStore))));
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
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        AgentInvocation invocation = new AgentInvocation(
                UUID.randomUUID().toString(), request.sessionId(), context.agentId(),
                context.taskId(), Instant.now());
        invocations.put(invocation.invocationId(), invocation);
        latestInvocationIds.put(request.sessionId(), invocation.invocationId());
        AgentContext invocationContext = context.withInvocation(invocation);
        return states.compute(request.sessionId(), (sessionId, previous) -> {
            AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
            invocation.start();
            publish(DefaultAgentEvent.of(
                    invocationContext, AgentEventType.AGENT_STARTED, request.objective(),
                    java.util.Map.of("taskId", context.taskId(), "iteration", running.iteration())));
            AgentEventSink publishingSink = event -> {
                eventSink.emit(event);
                mapLegacyEvent(invocationContext, event).ifPresent(this::publish);
            };
            try {
                AgentState result = Objects.requireNonNull(
                        agentLoop.run(request, invocationContext, running, publishingSink),
                        "agentLoop returned null state");
                if (result.status() == AgentState.Status.RUNNING) {
                    result = result.fail("agent loop finished without a terminal state");
                }
                invocation.finish(result);
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
        AgentEventType type = state.status() == AgentState.Status.COMPLETED
                ? AgentEventType.AGENT_COMPLETED : AgentEventType.AGENT_FAILED;
        String message = state.status() == AgentState.Status.COMPLETED ? state.output() : state.error();
        publish(DefaultAgentEvent.of(
                context, type, message, java.util.Map.of("status", state.status().name())));
    }

    private void publish(AgentEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException ignored) {
            // 领域事件观察端不得破坏 Runtime 执行。
        }
    }
}
