package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.AgentStreamEvent;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * 管理与连接解耦的 Agent Run，并以 {@link AgentStreamEvent} 作为唯一客户端协议。
 * 关键生命周期和快照写入 Run Store，高频 status/delta 仅保留在有界内存窗口。
 */
public final class AgentRunCoordinator {
    private static final int DEFAULT_MAX_RETAINED_RUNS = 1_000;
    private static final int DEFAULT_MAX_EVENTS_PER_RUN = 2_000;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.github.agentos.server.workspace.DesktopWorkspaceBridge workspaceBridge;

    private final AgentRunner runner;
    private final ExecutorService executor;
    private final AgentRunTaskRegistry taskRegistry;
    private final AgentRunStore store;
    private final ConcurrentMap<String, ManagedRun> runs = new ConcurrentHashMap<>();
    private final int maxRetainedRuns;
    private final int maxEventsPerRun;

    public AgentRunCoordinator(
            AgentRunner runner, ExecutorService executor, AgentRunTaskRegistry taskRegistry) {
        this(runner, executor, taskRegistry, new InMemoryAgentRunStore(),
                DEFAULT_MAX_RETAINED_RUNS, DEFAULT_MAX_EVENTS_PER_RUN);
    }

    public AgentRunCoordinator(
            AgentRunner runner, ExecutorService executor, AgentRunTaskRegistry taskRegistry,
            int maxRetainedRuns, int maxEventsPerRun) {
        this(runner, executor, taskRegistry, new InMemoryAgentRunStore(),
                maxRetainedRuns, maxEventsPerRun);
    }

    public AgentRunCoordinator(
            AgentRunner runner, ExecutorService executor, AgentRunTaskRegistry taskRegistry,
            AgentRunStore store, int maxRetainedRuns, int maxEventsPerRun) {
        if (maxRetainedRuns <= 0 || maxEventsPerRun <= 0) {
            throw new IllegalArgumentException("run and event retention limits must be positive");
        }
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.maxRetainedRuns = maxRetainedRuns;
        this.maxEventsPerRun = maxEventsPerRun;
    }

    public synchronized AgentRunSnapshot start(AgentRequest request, InvocationContext context) {
        return start(request, context, ignored -> { });
    }

    public synchronized AgentRunSnapshot start(
            AgentRequest request, InvocationContext context,
            Consumer<AgentRunSnapshot> completionListener) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(completionListener, "completionListener must not be null");
        if (!runner.ensureSessionOwner(request.sessionId(), context.userId())) {
            throw new SessionAccessDeniedException(request.sessionId());
        }
        if (runs.values().stream().anyMatch(run -> run.sessionId().equals(request.sessionId())
                && !run.terminal())) {
            throw new SessionAlreadyRunningException(request.sessionId());
        }
        makeRoomForRun();
        AgentRequest executionRequest = workspaceBridge == null
                ? request : workspaceBridge.prepare(request, context.userId());
        String runId = "run_" + UUID.randomUUID();
        ManagedRun run = new ManagedRun(
                runId, "turn_" + UUID.randomUUID(), "", request.sessionId(),
                context.agentId(), context.userId(), request.objective(), "",
                AgentState.ready(), maxEventsPerRun, completionListener);
        runs.put(runId, run);
        store.save(run.snapshot((AgentInvocation) null));
        boolean started;
        try {
            started = taskRegistry.start(request.sessionId(), executor,
                    () -> execute(run, executionRequest, context));
        } catch (RuntimeException exception) {
            runs.remove(runId, run);
            throw exception;
        }
        if (!started) {
            runs.remove(runId, run);
            throw new SessionAlreadyRunningException(request.sessionId());
        }
        return run.snapshot((AgentInvocation) null);
    }

    /** 审批在原 Run 上从 WAITING 恢复，保持 runId、turnId 和 seq 连续。 */
    public synchronized AgentRunSnapshot resume(
            String invocationId, PendingActionResolution resolution, String userId) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        String invocationKey = requireText(invocationId, "invocationId");
        String owner = requireText(userId, "userId");
        ManagedRun managed = runs.values().stream()
                .filter(candidate -> candidate.invocationId().equals(invocationKey))
                .findFirst().orElse(null);
        if (managed != null) {
            if (!managed.userId().equals(owner)) {
                throw new SessionAccessDeniedException(managed.sessionId());
            }
            if (managed.status() != AgentRunStatus.WAITING) {
                throw new SessionAlreadyRunningException(managed.sessionId());
            }
        }
        AgentCheckpoint checkpoint = runner.checkpoint(invocationKey)
                .orElseThrow(() -> new ResumeNotFoundException(invocationKey));
        if (!runner.ownsSession(checkpoint.sessionId(), owner)) {
            throw new SessionAccessDeniedException(checkpoint.sessionId());
        }
        PendingAction pending = Objects.requireNonNull(
                checkpoint.pendingAction(), "checkpoint has no pending action");
        if (!pending.pendingActionId().equals(resolution.pendingActionId())) {
            throw new IllegalArgumentException("pending action id does not match checkpoint");
        }
        ManagedRun run = managed;
        if (run == null) throw new ResumeNotFoundException(invocationKey);
        synchronized (run) {
            boolean started = taskRegistry.start(checkpoint.sessionId(), executor,
                    () -> executeResume(run, invocationKey, resolution));
            if (!started) throw new SessionAlreadyRunningException(checkpoint.sessionId());
            // 在恢复线程能够发布下一条事件前，先把 WAITING 状态与审批生命周期原子推进。
            run.resolveApproval(resolution);
        }
        return run.snapshot(runner.invocation(invocationKey).orElse(null));
    }

    public Optional<AgentRunSnapshot> find(String runId) {
        String id = requireText(runId, "runId");
        ManagedRun run = runs.get(id);
        return run == null ? store.find(id) : Optional.of(run.snapshot(currentInvocation(run)));
    }

    public Optional<AgentRunSnapshot> find(String runId, String userId) {
        return find(runId).filter(snapshot -> snapshot.userId().equals(userId));
    }

    public List<AgentRunSnapshot> list() {
        return runs.values().stream().map(run -> run.snapshot(currentInvocation(run)))
                .sorted(Comparator.comparing(AgentRunSnapshot::createdAt).reversed()).toList();
    }

    public List<AgentRunSnapshot> list(String userId) { return store.listByUser(userId); }

    /** 只返回可重建 conversation history 的持久化用户事件。 */
    public List<AgentStreamEvent> history(String sessionId) {
        return store.eventsBySession(requireText(sessionId, "sessionId")).stream()
                .filter(event -> event.visibility() == AgentStreamEvent.Visibility.USER).toList();
    }

    public Optional<SseEmitter> stream(String runId, long afterSeq) {
        if (afterSeq < 0) throw new IllegalArgumentException("afterSeq must not be negative");
        String id = requireText(runId, "runId");
        ManagedRun run = runs.get(id);
        AgentRunSnapshot snapshot = find(id).orElse(null);
        if (snapshot == null) return Optional.empty();
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter);
        if (run == null) {
            store.eventsAfter(id, afterSeq).forEach(subscriber::send);
            subscriber.complete();
            return Optional.of(emitter);
        }
        Runnable detach = () -> run.remove(subscriber);
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(ignored -> detach.run());
        run.subscribe(afterSeq, store.eventsAfter(id, afterSeq), subscriber);
        return Optional.of(emitter);
    }

    public Optional<SseEmitter> stream(String runId, long afterSeq, String userId) {
        if (find(runId, userId).isEmpty()) return Optional.empty();
        return stream(runId, afterSeq);
    }

    public Optional<CancelResult> cancel(String runId) {
        ManagedRun run = runs.get(requireText(runId, "runId"));
        if (run == null) return Optional.empty();
        if (run.status() == AgentRunStatus.WAITING) {
            run.cancelWaiting();
            run.notifyCompletion();
            return Optional.of(new CancelResult(true, run.snapshot(currentInvocation(run))));
        }
        boolean token = !run.terminal() && runner.cancel(run.sessionId(), "cancelled by user");
        boolean interrupt = !run.terminal() && taskRegistry.cancel(run.sessionId());
        return Optional.of(new CancelResult(token || interrupt, run.snapshot(currentInvocation(run))));
    }

    public Optional<CancelResult> cancel(String runId, String userId) {
        if (find(runId, userId).isEmpty()) return Optional.empty();
        return cancel(runId);
    }

    private void execute(ManagedRun run, AgentRequest request, InvocationContext context) {
        run.startExecution();
        boolean terminal;
        try {
            AgentRunner.AgentRunResult result = runner.runDetailed(
                    request, context, run::publish, AgentEventPublisher.NOOP);
            terminal = run.finish(result.state(),
                    runner.invocation(result.invocationId()).orElse(null));
        } catch (RuntimeException exception) {
            terminal = run.failUnexpected(exception, currentInvocation(run));
        }
        if (terminal) run.notifyCompletion();
    }

    private void executeResume(
            ManagedRun run, String invocationId, PendingActionResolution resolution) {
        boolean terminal;
        try {
            AgentState state = runner.resume(invocationId, resolution, run::publish);
            terminal = run.finish(state, runner.invocation(invocationId).orElse(null));
        } catch (RuntimeException exception) {
            terminal = run.failUnexpected(exception, runner.invocation(invocationId).orElse(null));
        }
        if (terminal) run.notifyCompletion();
    }

    private AgentInvocation currentInvocation(ManagedRun run) {
        if (!run.invocationId().isBlank()) {
            Optional<AgentInvocation> exact = runner.invocation(run.invocationId());
            if (exact.isPresent()) return exact.get();
        }
        return runner.latestInvocation(run.sessionId())
                .filter(invocation -> !invocation.startedAt().isBefore(run.createdAt()))
                .orElse(null);
    }

    private void makeRoomForRun() {
        while (runs.size() >= maxRetainedRuns) {
            ManagedRun oldest = runs.values().stream().filter(ManagedRun::terminal)
                    .min(Comparator.comparing(ManagedRun::createdAt))
                    .orElseThrow(() -> new RunCapacityExceededException(maxRetainedRuns));
            runs.remove(oldest.runId(), oldest);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record CancelResult(boolean interruptRequested, AgentRunSnapshot run) { }

    private final class ManagedRun {
        private final String runId;
        private final String turnId;
        private final String parentRunId;
        private final String sessionId;
        private final String rootAgentId;
        private final String userId;
        private final String objective;
        private final int maxEvents;
        private final Consumer<AgentRunSnapshot> completionListener;
        private final Instant createdAt = Instant.now();
        private final String messageItemId = "msg_" + UUID.randomUUID();
        private final List<AgentStreamEvent> events = new ArrayList<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private String invocationId;
        private AgentRunStatus status = AgentRunStatus.CREATED;
        private AgentState state;
        private PendingAction pendingAction;
        private AgentRunUsage usage = AgentRunUsage.ZERO;
        private final StringBuilder messageContent = new StringBuilder();
        private long seq;
        private Instant startedAt;
        private Instant updatedAt = createdAt;
        private Instant completedAt;
        private boolean messageStarted;
        private boolean messageCompleted;
        private boolean terminal;

        ManagedRun(
                String runId, String turnId, String parentRunId, String sessionId,
                String rootAgentId, String userId, String objective, String invocationId,
                AgentState state, int maxEvents,
                Consumer<AgentRunSnapshot> completionListener) {
            this.runId = runId;
            this.turnId = turnId;
            this.parentRunId = parentRunId;
            this.sessionId = sessionId;
            this.rootAgentId = rootAgentId;
            this.userId = userId;
            this.objective = objective == null ? "" : objective;
            this.invocationId = invocationId == null ? "" : invocationId;
            this.state = state;
            this.maxEvents = maxEvents;
            this.completionListener = completionListener;
        }

        synchronized void startExecution() {
            if (terminal) return;
            if (startedAt == null) startedAt = Instant.now();
            status = AgentRunStatus.RUNNING;
            pendingAction = null;
            updatedAt = Instant.now();
            append(AgentStreamEvent.Type.RUN_STARTED, runId, rootAgentId, parentRunId,
                    Map.of("objective", objective, "status", status.name()));
            append(AgentStreamEvent.Type.STATUS, runId, rootAgentId, parentRunId,
                    Map.of("text", "正在启动任务"));
            persistSnapshot(null);
        }

        synchronized void resolveApproval(PendingActionResolution resolution) {
            if (terminal || status != AgentRunStatus.WAITING) return;
            String toolCallId = pendingAction == null ? "" : String.valueOf(
                    pendingAction.payload().getOrDefault("toolCallId", pendingAction.pendingActionId()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("toolCallId", toolCallId);
            data.put("pendingActionId", resolution.pendingActionId());
            data.put("approved", resolution.approved());
            if (resolution.approved()) {
                append(AgentStreamEvent.Type.TOOL_APPROVED, toolCallId, rootAgentId, parentRunId, data);
            } else {
                data.put("error", Map.of("code", "TOOL_APPROVAL_REJECTED",
                        "message", "用户拒绝了工具调用", "retryable", false));
                append(AgentStreamEvent.Type.TOOL_FAILED, toolCallId, rootAgentId, parentRunId, data);
            }
            status = AgentRunStatus.RUNNING;
            pendingAction = null;
            append(AgentStreamEvent.Type.STATUS, runId, rootAgentId, parentRunId,
                    Map.of("text", resolution.approved() ? "已批准，正在恢复任务" : "正在处理拒绝操作"));
            persistSnapshot(null);
        }

        synchronized void publish(AgentRunEvent source) {
            if (terminal || source == null) return;
            if (source.type() == AgentRunEvent.Type.OUTPUT_DELTA) {
                ensureMessageStarted(agent(source));
                int offset = messageContent.length();
                messageContent.append(source.message());
                append(AgentStreamEvent.Type.MESSAGE_DELTA, messageItemId, agent(source), parent(source),
                        Map.of("delta", source.message(), "offset", offset,
                                "contentLength", messageContent.length()));
                return;
            }
            if (source.type() == AgentRunEvent.Type.RUN_COMPLETED) {
                completeMessage(source.message(), agent(source));
                return;
            }
            for (RuntimeStreamEventMapper.MappedEvent mapped : RuntimeStreamEventMapper.map(source)) {
                if (mapped.type() == AgentStreamEvent.Type.USAGE) {
                    usage = usage.plus(number(mapped.data().get("inputTokens")),
                            number(mapped.data().get("outputTokens")),
                            number(mapped.data().get("cachedTokens")));
                    Map<String, Object> data = new LinkedHashMap<>(mapped.data());
                    data.put("aggregate", usage);
                    append(mapped.type(), mapped.itemId(), mapped.agentId(), mapped.parentRunId(), data);
                } else {
                    append(mapped.type(), mapped.itemId(), mapped.agentId(),
                            mapped.parentRunId(), mapped.data());
                }
            }
        }

        synchronized boolean finish(AgentState finalState, AgentInvocation invocation) {
            if (terminal) return true;
            state = Objects.requireNonNull(finalState);
            observe(invocation);
            if (finalState.status() == AgentState.Status.WAITING) {
                status = AgentRunStatus.WAITING;
                pendingAction = invocation == null ? null : invocation.pendingAction();
                updatedAt = Instant.now();
                appendSnapshotEvent(AgentStreamEvent.Type.RUN_WAITING, runId, null);
                return false;
            }
            status = switch (finalState.status()) {
                case COMPLETED -> AgentRunStatus.COMPLETED;
                case CANCELLED -> AgentRunStatus.CANCELLED;
                default -> AgentRunStatus.FAILED;
            };
            if (status == AgentRunStatus.COMPLETED) completeMessage(finalState.output(), rootAgentId);
            completedAt = Instant.now();
            updatedAt = completedAt;
            AgentRunError error = status == AgentRunStatus.FAILED
                    ? runError(finalState.error(), false) : null;
            if (error != null) {
                append(AgentStreamEvent.Type.ERROR, runId, rootAgentId, parentRunId,
                        Map.of("error", error));
            }
            terminal = true;
            AgentStreamEvent.Type type = switch (status) {
                case COMPLETED -> AgentStreamEvent.Type.RUN_COMPLETED;
                case CANCELLED -> AgentStreamEvent.Type.RUN_CANCELLED;
                default -> AgentStreamEvent.Type.RUN_FAILED;
            };
            appendSnapshotEvent(type, runId, error);
            completeSubscribers();
            return true;
        }

        synchronized boolean failUnexpected(RuntimeException exception, AgentInvocation invocation) {
            if (terminal) return true;
            observe(invocation);
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            state = state == null ? AgentState.ready().fail(message) : state.fail(message);
            status = AgentRunStatus.FAILED;
            completedAt = Instant.now();
            updatedAt = completedAt;
            AgentRunError error = new AgentRunError("RUN_INTERNAL_ERROR", message, false);
            append(AgentStreamEvent.Type.ERROR, runId, rootAgentId, parentRunId,
                    Map.of("error", error));
            terminal = true;
            appendSnapshotEvent(AgentStreamEvent.Type.RUN_FAILED, runId,
                    error);
            completeSubscribers();
            return true;
        }

        synchronized void cancelWaiting() {
            if (terminal || status != AgentRunStatus.WAITING) return;
            state = state.cancel("cancelled by user");
            status = AgentRunStatus.CANCELLED;
            completedAt = Instant.now();
            updatedAt = completedAt;
            terminal = true;
            appendSnapshotEvent(AgentStreamEvent.Type.RUN_CANCELLED, runId, null);
            completeSubscribers();
        }

        synchronized void subscribe(
                long afterSeq, List<AgentStreamEvent> durable, Subscriber subscriber) {
            TreeMap<Long, AgentStreamEvent> merged = new TreeMap<>();
            durable.forEach(event -> merged.put(event.seq(), event));
            events.stream().filter(event -> event.seq() > afterSeq)
                    .forEach(event -> merged.put(event.seq(), event));
            for (AgentStreamEvent event : merged.values()) {
                if (!subscriber.send(event)) return;
            }
            if (terminal) subscriber.complete();
            else subscribers.add(subscriber);
        }

        synchronized void remove(Subscriber subscriber) { subscribers.remove(subscriber); }

        synchronized AgentRunSnapshot snapshot(AgentInvocation invocation) {
            observe(invocation);
            return buildSnapshot(runErrorFromState());
        }

        private AgentRunSnapshot buildSnapshot(AgentRunError error) {
            long duration = startedAt == null ? 0 : Math.max(0, Duration.between(startedAt,
                    completedAt == null ? updatedAt : completedAt).toMillis());
            return new AgentRunSnapshot(
                    AgentStreamEvent.SCHEMA_VERSION, runId, turnId, parentRunId, sessionId,
                    rootAgentId, userId, invocationId, status, pendingAction, seq,
                    createdAt, startedAt, updatedAt, completedAt, duration,
                    state == null ? "" : state.output(), error, usage);
        }

        private void ensureMessageStarted(String agentId) {
            if (messageStarted) return;
            messageStarted = true;
            append(AgentStreamEvent.Type.MESSAGE_STARTED, messageItemId, agentId, parentRunId,
                    Map.of("role", "assistant"));
        }

        private void completeMessage(String content, String agentId) {
            if (messageCompleted) return;
            ensureMessageStarted(agentId);
            String answer = content == null ? "" : content;
            messageContent.setLength(0);
            messageContent.append(answer);
            messageCompleted = true;
            append(AgentStreamEvent.Type.MESSAGE_COMPLETED, messageItemId, agentId, parentRunId,
                    Map.of("role", "assistant", "content", answer));
        }

        private void appendSnapshotEvent(
                AgentStreamEvent.Type type, String itemId, AgentRunError error) {
            updatedAt = Instant.now();
            long nextSeq = ++seq;
            AgentRunSnapshot snapshot = buildSnapshot(error);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("snapshot", snapshot);
            if (error != null) data.put("error", error);
            AgentStreamEvent event = event(type, itemId, rootAgentId, parentRunId, nextSeq, data);
            retain(event);
            if (type.durable()) store.append(event);
            store.save(snapshot);
            broadcast(event);
        }

        private void append(
                AgentStreamEvent.Type type, String itemId, String agentId,
                String parentId, Map<String, Object> data) {
            if (terminal) return;
            updatedAt = Instant.now();
            AgentStreamEvent event = event(type,
                    itemId == null || itemId.isBlank() ? runId : itemId,
                    agentId == null || agentId.isBlank() ? rootAgentId : agentId,
                    parentId == null ? "" : parentId, ++seq, data);
            retain(event);
            if (type.durable()) store.append(event);
            broadcast(event);
        }

        private AgentStreamEvent event(
                AgentStreamEvent.Type type, String itemId, String agentId,
                String parentId, long eventSeq, Map<String, Object> data) {
            return new AgentStreamEvent(
                    AgentStreamEvent.SCHEMA_VERSION, type.wireName(),
                    "evt_" + UUID.randomUUID(), runId, turnId, sessionId, itemId,
                    agentId, parentId, eventSeq, updatedAt,
                    AgentStreamEvent.Visibility.USER, data);
        }

        private void retain(AgentStreamEvent event) {
            events.add(event);
            if (events.size() > maxEvents) events.removeFirst();
        }

        private void broadcast(AgentStreamEvent event) {
            subscribers.removeIf(subscriber -> !subscriber.send(event));
        }

        private void completeSubscribers() {
            List.copyOf(subscribers).forEach(Subscriber::complete);
            subscribers.clear();
        }

        private void persistSnapshot(AgentInvocation invocation) { store.save(snapshot(invocation)); }

        private void observe(AgentInvocation invocation) {
            if (invocation == null) return;
            if (!invocationId.isBlank() && !invocationId.equals(invocation.invocationId())) return;
            invocationId = invocation.invocationId();
            pendingAction = invocation.pendingAction();
        }

        private AgentRunError runErrorFromState() {
            return status == AgentRunStatus.FAILED && state != null
                    ? runError(state.error(), false) : null;
        }

        void notifyCompletion() {
            try { completionListener.accept(snapshot((AgentInvocation) null)); }
            catch (RuntimeException ignored) { /* 回调不能反向破坏已落库终态。 */ }
        }

        synchronized boolean terminal() { return terminal; }
        synchronized AgentRunStatus status() { return status; }
        synchronized String invocationId() { return invocationId; }
        String runId() { return runId; }
        String sessionId() { return sessionId; }
        String userId() { return userId; }
        Instant createdAt() { return createdAt; }
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        private boolean connected = true;

        Subscriber(SseEmitter emitter) { this.emitter = emitter; }

        synchronized boolean send(AgentStreamEvent event) {
            if (!connected || event.visibility() != AgentStreamEvent.Visibility.USER) return false;
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.seq()))
                        .name(event.event()).data(event));
                return true;
            } catch (IOException | IllegalStateException exception) {
                connected = false;
                return false;
            }
        }

        synchronized void complete() {
            if (!connected) return;
            connected = false;
            emitter.complete();
        }
    }

    private static String agent(AgentRunEvent source) {
        Object value = source.data().get("agentId");
        return value == null ? "" : String.valueOf(value);
    }

    private static String parent(AgentRunEvent source) {
        Object value = source.data().get("parentRunId");
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static AgentRunError runError(String message, boolean retryable) {
        String text = message == null ? "" : message;
        String code = "human approval rejected".equals(text)
                ? "APPROVAL_REJECTED" : "RUN_FAILED";
        return new AgentRunError(code, text, retryable);
    }

    public static final class SessionAlreadyRunningException extends RuntimeException {
        public SessionAlreadyRunningException(String sessionId) {
            super("session already has a running task: " + sessionId);
        }
    }

    public static final class RunCapacityExceededException extends RuntimeException {
        public RunCapacityExceededException(int capacity) {
            super("agent run retention capacity exceeded: " + capacity);
        }
    }

    public static final class SessionAccessDeniedException extends RuntimeException {
        public SessionAccessDeniedException(String sessionId) {
            super("session not found: " + sessionId);
        }
    }

    public static final class ResumeNotFoundException extends RuntimeException {
        public ResumeNotFoundException(String invocationId) {
            super("waiting run not found for invocation: " + invocationId);
        }
    }
}
