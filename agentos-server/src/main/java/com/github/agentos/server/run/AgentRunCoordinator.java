package com.github.agentos.server.run;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private final AgentRunner runner;
    private final ExecutorService executor;
    private final AgentRunTaskRegistry taskRegistry;
    private final ConcurrentMap<String, ManagedRun> runs = new ConcurrentHashMap<>();

    /** 创建后台运行协调器。 */
    public AgentRunCoordinator(
            AgentRunner runner,
            ExecutorService executor,
            AgentRunTaskRegistry taskRegistry) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskRegistry = Objects.requireNonNull(
                taskRegistry, "taskRegistry must not be null");
    }

    /** 创建后台任务并立即返回可持久化的运行快照。 */
    public RunSnapshot start(AgentRequest request, InvocationContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        String runId = UUID.randomUUID().toString();
        AgentState initialState = runner.state(request.sessionId())
                .orElseGet(AgentState::ready)
                .startNextIteration();
        ManagedRun run = new ManagedRun(runId, request.sessionId(), initialState);
        runs.put(runId, run);

        boolean started;
        try {
            started = taskRegistry.start(request.sessionId(), executor, () -> execute(
                    run, request, context));
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

    /** 查询后台运行快照。 */
    public Optional<RunSnapshot> find(String runId) {
        ManagedRun run = runs.get(requireText(runId, "runId"));
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(run.snapshot(currentInvocation(run)));
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
            AgentState state = runner.run(request, context, run::publish);
            run.finish(state, currentInvocation(run));
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

    /** 可供页面恢复的后台运行快照。 */
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

    /** 可断点补播的事件信封。 */
    public record SequencedRunEvent(
            long sequence,
            String type,
            Instant createdAt,
            Object data) {
    }

    /** 显式取消请求结果。 */
    public record CancelResult(boolean interruptRequested, RunSnapshot run) {
    }

    /** 同一 session 已有后台或兼容流任务。 */
    public static final class SessionAlreadyRunningException extends RuntimeException {
        public SessionAlreadyRunningException(String sessionId) {
            super("session already has a running task: " + sessionId);
        }
    }

    private static final class ManagedRun {
        private final String runId;
        private final String sessionId;
        private final Instant createdAt = Instant.now();
        private final List<SequencedRunEvent> events = new ArrayList<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private AgentState state;
        private String invocationId = "";
        private PendingAction pendingAction;
        private long sequence;
        private Instant updatedAt;
        private boolean terminal;

        ManagedRun(String runId, String sessionId, AgentState initialState) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.state = initialState;
            this.updatedAt = createdAt;
        }

        synchronized void publish(AgentRunEvent event) {
            append(event.type().name().toLowerCase(Locale.ROOT), event);
        }

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
            events.add(event);
            broadcast(event);
            List.copyOf(subscribers).forEach(Subscriber::complete);
            subscribers.clear();
        }

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

        synchronized void remove(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

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

        private void append(String type, Object data) {
            updatedAt = Instant.now();
            SequencedRunEvent event = new SequencedRunEvent(
                    ++sequence, type, updatedAt, data);
            events.add(event);
            broadcast(event);
        }

        private void broadcast(SequencedRunEvent event) {
            subscribers.removeIf(subscriber -> !subscriber.send(event));
        }

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
