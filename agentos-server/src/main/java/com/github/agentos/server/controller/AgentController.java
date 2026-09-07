package com.github.agentos.server.controller;

import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.ChatStreamEvent;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.server.run.ChatEventMapper;
import com.github.agentos.server.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对外提供 Agent 运行和状态查询能力的 REST 控制器。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRunner runner;
    private final ExecutorService streamExecutor;
    private final AgentRunTaskRegistry taskRegistry;
    private final SessionHistoryService sessionHistoryService;

    /**
     * 创建 Agent REST 控制器。
     *
     * @param runner Agent 统一运行入口
     * @param sessionHistoryService 会话多轮历史服务
     */
    public AgentController(
            AgentRunner runner,
            ExecutorService streamExecutor,
            AgentRunTaskRegistry taskRegistry,
            SessionHistoryService sessionHistoryService) {
        this.runner = runner;
        this.streamExecutor = streamExecutor;
        this.taskRegistry = taskRegistry;
        this.sessionHistoryService = sessionHistoryService;
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
    public ResponseEntity<RunResponse> run(
            @RequestBody RunRequest request, HttpServletRequest httpRequest) {
        RunInvocation invocation = normalize(request, RequestIdentity.from(httpRequest));
        AgentRunner.AgentRunResult result = runner.runDetailed(
                invocation.request(),
                invocation.context(),
                AgentEventSink.NOOP,
                AgentEventPublisher.NOOP);
        RunResponse response = response(
                invocation.request().sessionId(), result.state(), result.invocationId());
        return ResponseEntity.created(URI.create(
                "/api/agents/" + invocation.request().sessionId() + "/state")).body(response);
    }

    /**
     * 异步执行 Agent，并以 SSE 依次输出 Planner、Tool、Observation、Decision 和终态事件。
     *
     * <p>该接口流式输出运行阶段，而模型规划响应仍使用结构化 JSON 一次性校验。</p>
     */
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestBody RunRequest request,
            HttpServletResponse response,
            HttpServletRequest httpRequest) {
        disableEventStreamBuffering(response);
        RunInvocation invocation = normalize(request, RequestIdentity.from(httpRequest));
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean connected = new AtomicBoolean(true);
        Runnable disconnect = () -> connected.set(false);
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(error -> disconnect.run());

        boolean started = taskRegistry.start(
                invocation.request().sessionId(), streamExecutor, () -> {
            try {
                AgentRunner.AgentRunResult result = runner.runDetailed(
                        invocation.request(),
                        invocation.context(),
                        event -> sendEvent(emitter, connected, event),
                        AgentEventPublisher.NOOP);
                send(emitter, connected, "state", response(
                        invocation.request().sessionId(),
                        result.state(),
                        result.invocationId()));
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

    private static void disableEventStreamBuffering(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }

    /** 请求停止指定会话的流式运行，并中断其虚拟线程或平台线程。 */
    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<StopResponse> stop(
            @PathVariable String sessionId, HttpServletRequest request) {
        String userId = RequestIdentity.from(request).userId();
        requireOwnedSession(sessionId, userId);
        boolean interruptRequested = taskRegistry.cancel(sessionId);
        StopResponse response = new StopResponse(
                sessionId,
                interruptRequested,
                runner.state(sessionId, userId).orElse(null));
        return ResponseEntity.status(
                        interruptRequested ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }

    private static void sendEvent(
            SseEmitter emitter, AtomicBoolean connected, AgentRunEvent event) {
        ChatStreamEvent chatEvent;
        try {
            chatEvent = ChatEventMapper.map(event);
        } catch (RuntimeException exception) {
            return;
        }
        if (chatEvent == null) {
            return;
        }
        send(emitter, connected, ChatEventMapper.eventName(chatEvent), chatEvent);
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

    private RunInvocation normalize(RunRequest request, RequestIdentity identity) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        String agentId = request.agentId() == null || request.agentId().isBlank()
                ? "main-agent"
                : request.agentId();
        String teamId = identity.teamId();
        String userId = identity.userId();
        String taskId = request.taskId() == null ? "" : request.taskId();
        requireOwnedOrNewSession(sessionId, userId);
        // 会话历史由统一注入点补齐：直答路径展开为原生多轮消息，规划路径随 attributes 下传。
        AgentRequest agentRequest = sessionHistoryService.withHistory(new AgentRequest(
                sessionId, request.input(),
                request.attributes() == null ? Map.of() : request.attributes()));
        return new RunInvocation(
                agentRequest,
                new InvocationContext(teamId, userId, agentId, taskId));
    }

    /**
     * 查询指定会话最新的 Agent 状态。
     *
     * @param sessionId 会话标识
     * @return 状态存在时返回 HTTP 200，否则返回 HTTP 404
     */
    @GetMapping("/{sessionId}/state")
    public ResponseEntity<AgentState> state(
            @PathVariable String sessionId, HttpServletRequest request) {
        return runner.state(sessionId, RequestIdentity.from(request).userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 查询会话当前等待处理的外部动作。 */
    @GetMapping("/{sessionId}/pending-action")
    public ResponseEntity<PendingActionResponse> pendingAction(
            @PathVariable String sessionId, HttpServletRequest request) {
        requireOwnedSession(sessionId, RequestIdentity.from(request).userId());
        return runner.latestInvocation(sessionId)
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
            @RequestBody ResolutionRequest body,
            HttpServletRequest request) {
        if (body == null || body.pendingActionId() == null
                || body.pendingActionId().isBlank()) {
            throw new IllegalArgumentException("pendingActionId must not be blank");
        }
        // 进程重启后 invocation 内存态可能丢失；checkpoint 仍在说明该调用确实处于 WAITING。
        AgentCheckpoint checkpoint = runner.checkpoint(invocationId).orElse(null);
        AgentInvocation invocation = runner.invocation(invocationId).orElse(null);
        if (invocation == null && checkpoint == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "invocation not found: " + invocationId);
        }
        if (invocation != null && invocation.status() != AgentRunStatus.WAITING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "invocation is not waiting for an external action: " + invocationId);
        }
        String sessionId = invocation != null
                ? invocation.sessionId() : checkpoint.sessionId();
        requireOwnedSession(sessionId, RequestIdentity.from(request).userId());
        AgentState state = runner.resume(invocationId, new PendingActionResolution(
                body.pendingActionId(), body.approved(),
                body.data() == null ? Map.of() : body.data()));
        return ResponseEntity.ok(resolvedResponse(sessionId, invocationId, state));
    }

    /** 使用执行结果携带的 InvocationId，避免并发完成边界查询到下一次运行。 */
    private RunResponse response(
            String sessionId, AgentState state, String invocationId) {
        AgentInvocation invocation = runner.invocation(invocationId).orElse(null);
        return new RunResponse(
                sessionId,
                invocationId,
                state,
                state.status() == AgentState.Status.WAITING && invocation != null
                        ? invocation.pendingAction() : null);
    }

    /** 审批恢复响应：优先内存 invocation，重启场景回退到已知 invocationId。 */
    private RunResponse resolvedResponse(String sessionId, String invocationId, AgentState state) {
        AgentInvocation invocation = runner.invocation(invocationId).orElse(null);
        return new RunResponse(
                sessionId,
                invocation == null ? invocationId : invocation.invocationId(),
                state,
                state.status() == AgentState.Status.WAITING && invocation != null
                        ? invocation.pendingAction() : null);
    }

    private void requireOwnedOrNewSession(String sessionId, String userId) {
        if (!runner.ensureSessionOwner(sessionId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
    }

    private void requireOwnedSession(String sessionId, String userId) {
        if (!runner.ownsSession(sessionId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
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

    private record RunInvocation(AgentRequest request, InvocationContext context) {
    }
}
