package com.github.agentos.server.run;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.ChatStreamEvent;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * 管理与浏览器连接解耦的后台 Agent 运行，并保存可按序号补播的运行事件。
 *
 * <p>当前实现使用进程内存保存运行和事件。客户端断开只移除订阅者，不会取消任务；
 * 任务只能通过显式取消接口中断。</p>
 */
public final class AgentRunCoordinator {

    private static final int DEFAULT_MAX_RETAINED_RUNS = 1_000;
    private static final int DEFAULT_MAX_EVENTS_PER_RUN = 2_000;

    private final AgentRunner runner;
    private final ExecutorService executor;
    private final AgentRunTaskRegistry taskRegistry;
    private final ConcurrentMap<String, ManagedRun> runs = new ConcurrentHashMap<>();
    private final int maxRetainedRuns;
    private final int maxEventsPerRun;

    /**
     * 创建后台运行协调器。
     */
    public AgentRunCoordinator(
            AgentRunner runner,
            ExecutorService executor,
            AgentRunTaskRegistry taskRegistry) {
        this(runner, executor, taskRegistry,
                DEFAULT_MAX_RETAINED_RUNS, DEFAULT_MAX_EVENTS_PER_RUN);
    }

    /**
     * 创建带运行记录和单次事件保留上限的后台运行协调器。
     */
    public AgentRunCoordinator(
            AgentRunner runner,
            ExecutorService executor,
            AgentRunTaskRegistry taskRegistry,
            int maxRetainedRuns,
            int maxEventsPerRun) {
        if (maxRetainedRuns <= 0 || maxEventsPerRun <= 0) {
            throw new IllegalArgumentException("run and event retention limits must be positive");
        }
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskRegistry = Objects.requireNonNull(
                taskRegistry, "taskRegistry must not be null");
        this.maxRetainedRuns = maxRetainedRuns;
        this.maxEventsPerRun = maxEventsPerRun;
    }

    /**
     * 创建后台任务并立即返回可持久化的运行快照。
     */
    public synchronized RunSnapshot start(AgentRequest request, InvocationContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        makeRoomForRun();
        String runId = UUID.randomUUID().toString();
        AgentState initialState = runner.state(request.sessionId())
                .orElseGet(AgentState::ready)
                .startNextIteration();
        ManagedRun run = new ManagedRun(
                runId, request.sessionId(), initialState, maxEventsPerRun);
        runs.put(runId, run);

        boolean started;
        try {
            started = taskRegistry.start(request.sessionId(), executor,
                    () -> execute(run, request, context)
            );
        } catch (RuntimeException exception) {
            runs.remove(runId, run);
            throw exception;
        }
        if (!started) {
            runs.remove(runId, run);
            throw new SessionAlreadyRunningException(request.sessionId());
        }
        return run.snapshot(null);
    }

    private void makeRoomForRun() {
        while (runs.size() >= maxRetainedRuns) {
            ManagedRun oldestTerminal = runs.values().stream()
                    .filter(ManagedRun::terminal)
                    .min(java.util.Comparator.comparing(ManagedRun::createdAt))
                    .orElseThrow(() -> new RunCapacityExceededException(maxRetainedRuns));
            runs.remove(oldestTerminal.runId(), oldestTerminal);
        }
    }

    /**
     * 查询后台运行快照。
     */
    public Optional<RunSnapshot> find(String runId) {
        ManagedRun run = runs.get(requireText(runId, "runId"));
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(run.snapshot(currentInvocation(run)));
    }

    /**
     * 列出当前进程内保留的全部后台运行，按创建时间倒序。
     *
     * <p>供管理面板展示运行台账；进程重启后列表清空。</p>
     */
    public List<RunSnapshot> list() {
        return runs.values().stream()
                .map(run -> run.snapshot(currentInvocation(run)))
                .sorted(java.util.Comparator.comparing(
                        RunSnapshot::createdAt).reversed())
                .toList();
    }

    /**
     * 从指定事件序号之后订阅运行事件；历史事件会先补播，然后继续发送实时事件。
     */
    public Optional<SseEmitter> stream(String runId, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("after sequence must not be negative");
        }
        ManagedRun run = runs.get(requireText(runId, "runId"));
        if (run == null) {
            return Optional.empty();
        }
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter);
        Runnable detach = () -> run.remove(subscriber);
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(ignored -> detach.run());
        run.subscribe(afterSequence, subscriber);
        return Optional.of(emitter);
    }

    /**
     * 显式请求取消指定 run，不受页面连接状态影响。
     *
     * <p>双通道取消：先设置 Runner 的协作式令牌（步骤/工具边界感知），
     * 再中断执行线程（唤醒阻塞 I/O）；任一通道命中即视为取消已受理。</p>
     */
    public Optional<CancelResult> cancel(String runId) {
        ManagedRun run = runs.get(requireText(runId, "runId"));
        if (run == null) {
            return Optional.empty();
        }
        boolean tokenCancelled = !run.terminal()
                && runner.cancel(run.sessionId(), "cancelled by user");
        boolean interruptRequested = !run.terminal()
                && taskRegistry.cancel(run.sessionId());
        return Optional.of(new CancelResult(
                tokenCancelled || interruptRequested, run.snapshot(currentInvocation(run))));
    }

    private void execute(ManagedRun run, AgentRequest request, InvocationContext context) {
        try {
            AgentRunner.AgentRunResult result = runner.runDetailed(
                    request, context, run::publish,
                    AgentEventPublisher.NOOP);
            run.finish(
                    result.state(),
                    runner.invocation(result.invocationId()).orElse(null));
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            run.finish(run.state().fail(message), currentInvocation(run));
        }
    }

    private AgentInvocation currentInvocation(ManagedRun run) {
        return runner.latestInvocation(run.sessionId())
                .filter(invocation -> !invocation.startedAt().isBefore(run.createdAt()))
                .orElse(null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /**
     * 可供页面恢复的后台运行快照。
     */
    public record RunSnapshot(
            String runId,
            String sessionId,
            String invocationId,
            AgentState state,
            PendingAction pendingAction,
            long lastSequence,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 可断点补播的事件信封。
     */
    public record SequencedRunEvent(
            long sequence,
            String type,
            Instant createdAt,
            Object data) {
    }

    /**
     * 显式取消请求结果。
     */
    public record CancelResult(boolean interruptRequested, RunSnapshot run) {
    }

    /**
     * 同一 session 已有后台或兼容流任务。
     */
    public static final class SessionAlreadyRunningException extends RuntimeException {
        public SessionAlreadyRunningException(String sessionId) {
            super("session already has a running task: " + sessionId);
        }
    }

    /**
     * 保留表已满且没有可淘汰的终态运行。
     */
    public static final class RunCapacityExceededException extends RuntimeException {
        public RunCapacityExceededException(int capacity) {
            super("agent run retention capacity exceeded: " + capacity);
        }
    }

    /**
     * 单次后台运行的进程内可变状态，负责事件留存、SSE 订阅和终态收口。
     *
     * <p>除创建后不再变化的标识和保留上限外，其余字段都由本对象的监视器保护。
     * 发布事件、生成快照、订阅补播和结束运行均通过 {@code synchronized} 方法串行化，
     * 从而保证事件序号单调递增，并避免订阅者错过“历史补播到实时推送”之间的事件。</p>
     */
    private static final class ManagedRun {
        /**
         * 后台运行的唯一标识，对应 {@code /api/agent-runs/{runId}}。
         */
        private final String runId;
        /**
         * Agent 会话标识，同时也是任务注册表中的并发互斥键。
         */
        private final String sessionId;
        /**
         * 当前运行最多保留的事件数量；超出后淘汰最早事件。
         */
        private final int maxEvents;
        /**
         * 运行记录创建时间，用于排序和过滤创建前的 Invocation。
         */
        private final Instant createdAt = Instant.now();
        /**
         * 按序号升序保存的可补播事件窗口。
         */
        private final List<SequencedRunEvent> events = new ArrayList<>();
        /**
         * 当前仍保持连接、等待实时事件的 SSE 订阅者。
         */
        private final List<Subscriber> subscribers = new ArrayList<>();
        /**
         * 最近一次可见的 Agent 运行状态。
         */
        private AgentState state;
        /**
         * 与本次后台运行关联的 Invocation 标识；尚未观察到时为空。
         */
        private String invocationId = "";
        /**
         * 当前等待处理的人工审批动作；没有待审批动作时为空。
         */
        private PendingAction pendingAction;
        /**
         * 已分配的最后事件序号，在本次运行内严格单调递增。
         */
        private long sequence;
        /**
         * 状态或事件最后更新时间。
         */
        private Instant updatedAt;
        /**
         * 是否已经写入最终状态事件并关闭全部订阅者。
         */
        private boolean terminal;

        /**
         * 创建一条尚未结束、事件窗口为空的后台运行记录。
         */
        ManagedRun(String runId, String sessionId, AgentState initialState, int maxEvents) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.state = initialState;
            this.maxEvents = maxEvents;
            this.updatedAt = createdAt;
        }

        /**
         * 将内核事件映射为对话展示事件后加入补播窗口，并实时广播给当前订阅者。
         *
         * <p>映射由 {@link ChatEventMapper} 完成：与对话展示无关的事件（用量统计等）
         * 不会占用事件窗口；映射失败不会中断运行。</p>
         */
        synchronized void publish(AgentRunEvent event) {
            ChatStreamEvent chatEvent;
            try {
                chatEvent = ChatEventMapper.map(event);
            } catch (RuntimeException exception) {
                return;
            }
            if (chatEvent != null) {
                append(ChatEventMapper.eventName(chatEvent), chatEvent);
            }
        }

        /**
         * 原子地收口运行：更新最终状态、追加最终快照事件并完成全部 SSE 连接。
         * 重复调用会被忽略，确保终态事件只发布一次。
         */
        synchronized void finish(AgentState finalState, AgentInvocation invocation) {
            if (terminal) {
                return;
            }
            state = Objects.requireNonNull(finalState, "finalState must not be null");
            observe(invocation);
            terminal = true;
            updatedAt = Instant.now();
            long nextSequence = ++sequence;
            RunSnapshot snapshot = snapshot(invocation);
            SequencedRunEvent event = new SequencedRunEvent(
                    nextSequence, "state", updatedAt, snapshot);
            addRetainedEvent(event);
            broadcast(event);
            List.copyOf(subscribers).forEach(Subscriber::complete);
            subscribers.clear();
        }

        /**
         * 先补播指定游标之后的历史事件，再将连接加入实时订阅列表。
         * 若运行已经结束，则补播完成后立即关闭连接。
         */
        synchronized void subscribe(long afterSequence, Subscriber subscriber) {
            for (SequencedRunEvent event : events) {
                if (event.sequence() > afterSequence && !subscriber.send(event)) {
                    return;
                }
            }
            if (terminal) {
                subscriber.complete();
            } else {
                subscribers.add(subscriber);
            }
        }

        /**
         * 移除已经完成、超时或断开的订阅者。
         */
        synchronized void remove(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

        /**
         * 观察最新 Invocation 信息并生成不会暴露内部集合的运行快照。
         */
        synchronized RunSnapshot snapshot(AgentInvocation invocation) {
            observe(invocation);
            return new RunSnapshot(
                    runId, sessionId, invocationId, state, pendingAction,
                    sequence, createdAt, updatedAt);
        }

        synchronized AgentState state() {
            return state;
        }

        synchronized boolean terminal() {
            return terminal;
        }

        String sessionId() {
            return sessionId;
        }

        Instant createdAt() {
            return createdAt;
        }

        String runId() {
            return runId;
        }

        /**
         * 分配新序号、保留事件并广播；调用方必须持有本对象监视器。
         */
        private void append(String type, Object data) {
            updatedAt = Instant.now();
            SequencedRunEvent event = new SequencedRunEvent(
                    ++sequence, type, updatedAt, data);
            addRetainedEvent(event);
            broadcast(event);
        }

        /**
         * 将事件加入有界补播窗口；调用方必须持有本对象监视器。
         */
        private void addRetainedEvent(SequencedRunEvent event) {
            events.add(event);
            if (events.size() > maxEvents) {
                events.remove(0);
            }
        }

        /**
         * 广播事件，并清理发送失败的订阅者；调用方必须持有本对象监视器。
         */
        private void broadcast(SequencedRunEvent event) {
            subscribers.removeIf(subscriber -> !subscriber.send(event));
        }

        /**
         * 采纳属于本次运行的 Invocation 标识和待审批动作。
         * 已绑定 Invocation 后会拒绝其他 Invocation，防止同一 Session 的后续运行污染快照。
         */
        private void observe(AgentInvocation invocation) {
            if (invocation == null) {
                return;
            }
            if (!invocationId.isEmpty()
                    && !invocationId.equals(invocation.invocationId())) {
                return;
            }
            invocationId = invocation.invocationId();
            pendingAction = invocation.pendingAction();
        }
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        private boolean connected = true;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        synchronized boolean send(SequencedRunEvent event) {
            if (!connected) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(event));
                return true;
            } catch (IOException | IllegalStateException exception) {
                connected = false;
                return false;
            }
        }

        synchronized void complete() {
            if (!connected) {
                return;
            }
            connected = false;
            emitter.complete();
        }
    }
}
