package com.github.agentos.server.controller;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.run.AgentRunCoordinator;
import com.github.agentos.server.run.AgentRunSnapshot;
import com.github.agentos.kernel.AgentStreamEvent;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 提供可在页面刷新后查询、补播和继续订阅的后台 Agent 运行 API。 */
@RestController
@RequestMapping("/api/agent-runs")
public final class BackgroundAgentRunController {

    private final AgentRunCoordinator coordinator;
    private final SessionHistoryService sessionHistoryService;
    private final SessionAuthorization authorization;

    /** 创建后台运行控制器。 */
    public BackgroundAgentRunController(
            AgentRunCoordinator coordinator,
            SessionHistoryService sessionHistoryService,
            SessionAuthorization authorization) {
        this.coordinator = coordinator;
        this.sessionHistoryService = sessionHistoryService;
        this.authorization = authorization;
    }

    /** 创建与 HTTP 连接生命周期无关的后台运行。 */
    @PostMapping
    public ResponseEntity<AgentRunSnapshot> start(
            @RequestBody StartRunRequest body, HttpServletRequest request) {
        RunInvocation invocation = normalize(body, request);
        try {
            AgentRunSnapshot run = coordinator.start(
                    invocation.request(), invocation.context());
            return ResponseEntity.accepted()
                    .location(URI.create("/api/agent-runs/" + run.runId()))
                    .body(run);
        } catch (AgentRunCoordinator.SessionAlreadyRunningException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (AgentRunCoordinator.RunCapacityExceededException exception) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        } catch (AgentRunCoordinator.SessionAccessDeniedException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
    }

    /** 列出进程内保留的全部后台运行，按创建时间倒序。 */
    @GetMapping
    public List<AgentRunSnapshot> list(HttpServletRequest request) {
        return coordinator.list(RequestIdentity.from(request).userId()).stream()
                .filter(run -> authorization.owns(run.sessionId(), request))
                .toList();
    }

    /** 返回会话可持久化回放的用户事件，不包含临时 status 和 token delta。 */
    @GetMapping("/history")
    public List<AgentStreamEvent> history(
            @RequestParam String sessionId, HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        return coordinator.history(sessionId);
    }

    /** 查询运行状态、最终结果和当前事件游标。 */
    @GetMapping("/{runId}")
    public ResponseEntity<AgentRunSnapshot> get(
            @PathVariable String runId, HttpServletRequest request) {
        AgentRunSnapshot run = coordinator.find(
                runId, RequestIdentity.from(request).userId()).orElse(null);
        if (run == null || !authorization.owns(run.sessionId(), request)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(run);
    }

    /** 补播游标之后的事件，并在任务仍运行时继续保持实时 SSE 订阅。 */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response,
            HttpServletRequest request) {
        disableEventStreamBuffering(response);
        long cursor = Math.max(afterSeq, parseLastEventId(lastEventId));
        AgentRunSnapshot run = coordinator.find(
                runId, RequestIdentity.from(request).userId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
        authorization.requireOwned(run.sessionId(), request);
        return coordinator.stream(
                runId, cursor, RequestIdentity.from(request).userId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
    }

    private static void disableEventStreamBuffering(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }

    /** 只有显式调用该接口才会取消后台任务。 */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<AgentRunCoordinator.CancelResult> cancel(
            @PathVariable String runId, HttpServletRequest request) {
        AgentRunSnapshot run = coordinator.find(
                runId, RequestIdentity.from(request).userId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
        authorization.requireOwned(run.sessionId(), request);
        AgentRunCoordinator.CancelResult result = coordinator.cancel(
                runId, RequestIdentity.from(request).userId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
        return ResponseEntity.status(
                result.interruptRequested() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    /** 提交审批结果并在后台恢复原 invocation，避免长任务阻塞审批请求。 */
    @PostMapping("/invocations/{invocationId}/resolution")
    public ResponseEntity<AgentRunSnapshot> resolve(
            @PathVariable String invocationId,
            @RequestBody ResolutionRequest body,
            HttpServletRequest request) {
        if (body == null || body.pendingActionId() == null
                || body.pendingActionId().isBlank()) {
            throw new IllegalArgumentException("pendingActionId must not be blank");
        }
        try {
            AgentRunSnapshot run = coordinator.resume(
                    invocationId,
                    new PendingActionResolution(
                            body.pendingActionId(), body.approved(),
                            body.data() == null ? Map.of() : body.data()),
                    RequestIdentity.from(request).userId());
            return ResponseEntity.accepted()
                    .location(URI.create("/api/agent-runs/" + run.runId()))
                    .body(run);
        } catch (AgentRunCoordinator.ResumeNotFoundException
                | AgentRunCoordinator.SessionAccessDeniedException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invocation not found");
        } catch (AgentRunCoordinator.SessionAlreadyRunningException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private RunInvocation normalize(StartRunRequest body, HttpServletRequest httpRequest) {
        if (body == null || body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = textOr(body.sessionId(), UUID.randomUUID().toString());
        RequestIdentity identity = RequestIdentity.from(httpRequest);
        authorization.claim(sessionId, httpRequest);
        // 后台运行是控制台唯一的运行入口，必须在这里注入会话历史，否则每轮都是新对话。
        AgentRequest request = sessionHistoryService.withHistory(new AgentRequest(
                sessionId, body.input(),
                body.attributes() == null ? Map.of() : body.attributes()));
        return new RunInvocation(
                request,
                new InvocationContext(
                        identity.teamId(),
                        identity.userId(),
                        textOr(body.agentId(), "plan-execute-agent"),
                        body.taskId() == null ? "" : body.taskId()));
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            return Math.max(parsed, 0L);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 后台运行创建请求。 */
    public record StartRunRequest(
            String teamId,
            String userId,
            String agentId,
            String sessionId,
            String taskId,
            String input,
            Map<String, Object> attributes) {
    }

    /** 后台审批恢复请求。 */
    public record ResolutionRequest(
            String pendingActionId,
            boolean approved,
            Map<String, Object> data) {
    }

    private record RunInvocation(AgentRequest request, InvocationContext context) {
    }
}
