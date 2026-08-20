package com.github.agentos.server.controller;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.server.run.AgentRunCoordinator;
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

    /** 创建后台运行控制器。 */
    public BackgroundAgentRunController(AgentRunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 创建与 HTTP 连接生命周期无关的后台运行。 */
    @PostMapping
    public ResponseEntity<AgentRunCoordinator.RunSnapshot> start(
            @RequestBody StartRunRequest body) {
        RunInvocation invocation = normalize(body);
        try {
            AgentRunCoordinator.RunSnapshot run = coordinator.start(
                    invocation.request(), invocation.context());
            return ResponseEntity.accepted()
                    .location(URI.create("/api/agent-runs/" + run.runId()))
                    .body(run);
        } catch (AgentRunCoordinator.SessionAlreadyRunningException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    /** 列出进程内保留的全部后台运行，按创建时间倒序。 */
    @GetMapping
    public List<AgentRunCoordinator.RunSnapshot> list() {
        return coordinator.list();
    }

    /** 查询运行状态、最终结果和当前事件游标。 */
    @GetMapping("/{runId}")
    public ResponseEntity<AgentRunCoordinator.RunSnapshot> get(@PathVariable String runId) {
        return coordinator.find(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 补播游标之后的事件，并在任务仍运行时继续保持实时 SSE 订阅。 */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = Math.max(after, parseLastEventId(lastEventId));
        return coordinator.stream(runId, cursor).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
    }

    /** 只有显式调用该接口才会取消后台任务。 */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<AgentRunCoordinator.CancelResult> cancel(@PathVariable String runId) {
        AgentRunCoordinator.CancelResult result = coordinator.cancel(runId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
        return ResponseEntity.status(
                result.interruptRequested() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    private static RunInvocation normalize(StartRunRequest body) {
        if (body == null || body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = textOr(body.sessionId(), UUID.randomUUID().toString());
        return new RunInvocation(
                new AgentRequest(sessionId, body.input(),
                        body.attributes() == null ? Map.of() : body.attributes()),
                new InvocationContext(
                        textOr(body.teamId(), "default-team"),
                        textOr(body.userId(), "default-user"),
                        textOr(body.agentId(), "main-agent"),
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

    private record RunInvocation(AgentRequest request, InvocationContext context) {
    }
}
