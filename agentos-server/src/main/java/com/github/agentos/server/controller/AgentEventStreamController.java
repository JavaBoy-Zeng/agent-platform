package com.github.agentos.server.controller;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将 AgentEvent、兼容运行事件和 partial token 分通道输出的 SSE 控制器。 */
@RestController
@RequestMapping("/api/agents")
public final class AgentEventStreamController {

    private final AgentRunner runner;
    private final ExecutorService executor;
    private final AgentRunTaskRegistry taskRegistry;
    private final SessionHistoryService sessionHistoryService;

    /** 创建领域事件 SSE 控制器。 */
    public AgentEventStreamController(
            AgentRunner runner, ExecutorService executor,
            AgentRunTaskRegistry taskRegistry,
            SessionHistoryService sessionHistoryService) {
        this.runner = runner;
        this.executor = executor;
        this.taskRegistry = taskRegistry;
        this.sessionHistoryService = sessionHistoryService;
    }

    /**
     * 异步执行 Agent：领域事件使用 {@code domain.*}，partial token 使用 {@code token}，
     * 其他旧版事件使用 {@code runtime.*}。
     */
    @PostMapping(value = "/runs/event-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestBody EventStreamRequest body, HttpServletResponse response) {
        disableEventStreamBuffering(response);
        EventStreamInvocation invocation = normalize(body);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean connected = new AtomicBoolean(true);
        Runnable disconnect = () -> connected.set(false);
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(ignored -> disconnect.run());

        boolean started = taskRegistry.start(invocation.request().sessionId(), executor, () -> {
            try {
                AgentState state = runner.run(
                        invocation.request(), invocation.context(),
                        event -> sendLegacy(emitter, connected, event),
                        event -> sendDomain(emitter, connected, event));
                send(emitter, connected, "state", new EventStreamResponse(
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

    private static void disableEventStreamBuffering(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private static void sendDomain(
            SseEmitter emitter, AtomicBoolean connected, AgentEvent event) {
        send(emitter, connected,
                "domain." + event.type().name().toLowerCase(Locale.ROOT), event);
    }

    private static void sendLegacy(
            SseEmitter emitter, AtomicBoolean connected, AgentRunEvent event) {
        String name = event.type() == AgentRunEvent.Type.OUTPUT_DELTA
                ? "token"
                : "runtime." + event.type().name().toLowerCase(Locale.ROOT);
        send(emitter, connected, name, event);
    }

    private static void send(
            SseEmitter emitter, AtomicBoolean connected, String name, Object value) {
        if (!connected.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(value));
        } catch (IOException | IllegalStateException exception) {
            connected.set(false);
        }
    }

    private EventStreamInvocation normalize(EventStreamRequest body) {
        if (body == null || body.input() == null || body.input().isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        String sessionId = textOr(body.sessionId(), UUID.randomUUID().toString());
        String agentId = textOr(body.agentId(), "main-agent");
        String teamId = textOr(body.teamId(), "default-team");
        String userId = textOr(body.userId(), "default-user");
        return new EventStreamInvocation(
                sessionHistoryService.withHistory(new AgentRequest(sessionId, body.input(),
                        body.attributes() == null ? Map.of() : body.attributes())),
                new InvocationContext(teamId, userId, agentId,
                        body.taskId() == null ? "" : body.taskId()));
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 领域事件流请求体。 */
    public record EventStreamRequest(
            String teamId, String userId, String agentId, String sessionId,
            String taskId, String input, Map<String, Object> attributes) {
    }

    /** 领域事件流最终状态。 */
    public record EventStreamResponse(String sessionId, AgentState state) {
    }

    private record EventStreamInvocation(AgentRequest request, InvocationContext context) {
    }
}
