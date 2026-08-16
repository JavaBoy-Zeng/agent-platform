package com.github.agentos.server.controller;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对外提供 Agent 运行和状态查询能力的 REST 控制器。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRuntime runtime;
    private final ExecutorService streamExecutor;
    private final AgentRunTaskRegistry taskRegistry;

    /**
     * 创建 Agent REST 控制器。
     *
     * @param runtime Agent 统一运行入口
     */
    public AgentController(
            AgentRuntime runtime,
            ExecutorService streamExecutor,
            AgentRunTaskRegistry taskRegistry) {
        this.runtime = runtime;
        this.streamExecutor = streamExecutor;
        this.taskRegistry = taskRegistry;
    }

    /**
     * 创建并同步执行一次 Agent 运行。
     *
     * <p>当请求未指定 Agent 标识或会话标识时，接口会分别使用默认 Agent 标识和随机会话标识。</p>
     *
     * @param request Agent 运行请求
     * @return HTTP 201 响应，其中包含会话标识和最终运行状态
     * @throws IllegalArgumentException 当用户输入为空时抛出
     */
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest request) {
        RunInvocation invocation = normalize(request);
        AgentState state = runtime.run(invocation.request(), invocation.context());
        RunResponse response = response(invocation.request().sessionId(), state);
        return ResponseEntity.created(URI.create(
                "/api/agents/" + invocation.request().sessionId() + "/state")).body(response);
    }

    /**
     * 异步执行 Agent，并以 SSE 依次输出 Planner、Tool、Observation、Decision 和终态事件。
     *
     * <p>该接口流式输出运行阶段，而模型规划响应仍使用结构化 JSON 一次性校验。</p>
     */
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody RunRequest request) {
        RunInvocation invocation = normalize(request);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean connected = new AtomicBoolean(true);
        Runnable disconnect = () -> connected.set(false);
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(error -> disconnect.run());

        boolean started = taskRegistry.start(
                invocation.request().sessionId(), streamExecutor, () -> {
            try {
                AgentState state = runtime.run(
                        invocation.request(),
                        invocation.context(),
                        event -> sendEvent(emitter, connected, event));
                send(emitter, connected, "state", response(
                        invocation.request().sessionId(), state));
                if (connected.get()) {
                    emitter.complete();
                }
            } catch (RuntimeException exception) {
                send(emitter, connected, "stream-error", Map.of(
                        "sessionId", invocation.request().sessionId(),
                        "detail", exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage()));
                if (connected.get()) {
                    emitter.completeWithError(exception);
                }
            }
        });
        if (!started) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "session already has a running stream: " + invocation.request().sessionId());
        }
        return emitter;
    }

    /** 请求停止指定会话的流式运行，并中断其虚拟线程或平台线程。 */
    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<StopResponse> stop(@PathVariable String sessionId) {
        boolean interruptRequested = taskRegistry.cancel(sessionId);
        StopResponse response = new StopResponse(
                sessionId,
                interruptRequested,
                runtime.state(sessionId).orElse(null));
        return ResponseEntity.status(
                        interruptRequested ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }

    private static void sendEvent(
            SseEmitter emitter, AtomicBoolean connected, AgentRunEvent event) {
        send(emitter, connected, event.type().name().toLowerCase(Locale.ROOT), event);
    }

    private static void send(
            SseEmitter emitter, AtomicBoolean connected, String name, Object data) {
        if (!connected.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException exception) {
            connected.set(false);
        }
    }

    private static RunInvocation normalize(RunRequest request) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        String agentId = request.agentId() == null || request.agentId().isBlank()
                ? "main-agent"
                : request.agentId();
        String teamId = request.teamId() == null || request.teamId().isBlank()
                ? "default-team"
                : request.teamId();
        String userId = request.userId() == null || request.userId().isBlank()
                ? "default-user"
                : request.userId();
        String taskId = request.taskId() == null ? "" : request.taskId();
        Map<String, Object> attributes = request.attributes() == null
                ? Map.of()
                : request.attributes();
        return new RunInvocation(
                new AgentRequest(sessionId, request.input(), attributes),
                new AgentContext(teamId, userId, agentId, taskId));
    }

    /**
     * 查询指定会话最新的 Agent 状态。
     *
     * @param sessionId 会话标识
     * @return 状态存在时返回 HTTP 200，否则返回 HTTP 404
     */
    @GetMapping("/{sessionId}/state")
    public ResponseEntity<AgentState> state(@PathVariable String sessionId) {
        return runtime.state(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 查询会话当前等待处理的外部动作。 */
    @GetMapping("/{sessionId}/pending-action")
    public ResponseEntity<PendingActionResponse> pendingAction(@PathVariable String sessionId) {
        return runtime.latestInvocation(sessionId)
                .filter(invocation -> invocation.status() == AgentRunStatus.WAITING
                        && invocation.pendingAction() != null)
                .map(invocation -> ResponseEntity.ok(new PendingActionResponse(
                        sessionId, invocation.invocationId(), invocation.pendingAction())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 批准或拒绝挂起动作，并使用原 Invocation 从 Checkpoint 恢复。 */
    @PostMapping("/invocations/{invocationId}/resolution")
    public ResponseEntity<RunResponse> resolve(
            @PathVariable String invocationId,
            @RequestBody ResolutionRequest request) {
        if (request == null || request.pendingActionId() == null
                || request.pendingActionId().isBlank()) {
            throw new IllegalArgumentException("pendingActionId must not be blank");
        }
        AgentInvocation invocation = runtime.invocation(invocationId).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "invocation not found: " + invocationId));
        if (invocation.status() != AgentRunStatus.WAITING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "invocation is not waiting for an external action: " + invocationId);
        }
        AgentState state = runtime.resume(invocationId, new PendingActionResolution(
                request.pendingActionId(), request.approved(),
                request.data() == null ? Map.of() : request.data()));
        return ResponseEntity.ok(response(invocation.sessionId(), state));
    }

    private RunResponse response(String sessionId, AgentState state) {
        AgentInvocation invocation = runtime.latestInvocation(sessionId).orElse(null);
        return new RunResponse(
                sessionId,
                invocation == null ? "" : invocation.invocationId(),
                state,
                state.status() == AgentState.Status.WAITING && invocation != null
                        ? invocation.pendingAction() : null);
    }

    /**
     * Agent 运行接口的请求体。
     *
     * @param agentId Agent 标识；为空时使用 {@code main-agent}
     * @param sessionId 会话标识；为空时自动生成
     * @param input 用户任务指令
     * @param attributes 传递给 Agent 上下文的扩展属性
     */
    public record RunRequest(
            String teamId,
            String userId,
            String agentId,
            String sessionId,
            String taskId,
            String input,
            Map<String, Object> attributes) {
    }

    /**
     * Agent 运行接口的响应体。
     *
     * @param sessionId 本次运行使用的会话标识
     * @param state Agent 最终运行状态
     */
    public record RunResponse(
            String sessionId,
            String invocationId,
            AgentState state,
            PendingAction pendingAction) {
    }

    /** 待处理动作查询结果。 */
    public record PendingActionResponse(
            String sessionId, String invocationId, PendingAction pendingAction) {
    }

    /** 外部动作处理请求。 */
    public record ResolutionRequest(
            String pendingActionId, boolean approved, Map<String, Object> data) {
    }

    /** 停止请求的受理结果；最终 CANCELLED 状态可继续通过状态接口查询。 */
    public record StopResponse(
            String sessionId, boolean interruptRequested, AgentState state) {
    }

    private record RunInvocation(AgentRequest request, AgentContext context) {
    }
}
