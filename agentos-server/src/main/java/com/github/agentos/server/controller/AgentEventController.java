package com.github.agentos.server.controller;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.EventActions;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 领域事件轨迹查询接口。
 *
 * <p>事件是 Runtime 的一等记录：计划创建、步骤执行、模型与工具调用、审批挂起
 * 都以 {@link AgentEvent} 形式落库。该接口把已持久化的事件按 Invocation 分组
 * 暴露给管理面板，使计划复盘、轨迹审查不再依赖前端本地缓存的文本。</p>
 *
 * <p>只读当前存储快照；{@code memory} 持久化模式下进程重启后事件清空。</p>
 */
@RestController
@RequestMapping("/api/events")
public final class AgentEventController {

    private final AgentEventStore eventStore;
    private final SessionAuthorization authorization;

    /** 创建领域事件查询接口。 */
    public AgentEventController(AgentEventStore eventStore, SessionAuthorization authorization) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.authorization = Objects.requireNonNull(
                authorization, "authorization must not be null");
    }

    /**
     * 按会话返回全部 Invocation 的事件轨迹，最近的 Invocation 排在最前。
     *
     * @param sessionId 会话标识
     * @param type 可选事件类型过滤；不合法的名称按未过滤处理
     */
    @GetMapping
    public List<InvocationTrace> bySession(
            @RequestParam String sessionId,
            @RequestParam(required = false) String type,
            HttpServletRequest request) {
        authorization.requireOwned(sessionId, request);
        AgentEventType filter = parseType(type);
        Map<String, List<AgentEvent>> grouped = new LinkedHashMap<>();
        for (AgentEvent event : eventStore.findBySessionId(sessionId)) {
            if (filter != null && event.type() != filter) {
                continue;
            }
            grouped.computeIfAbsent(event.invocationId(), key -> new java.util.ArrayList<>())
                    .add(event);
        }
        // 按首个事件的时间戳倒序：ISO 字符串在省略小数秒时无法正确比较，必须用 Instant。
        return grouped.values().stream()
                .sorted(java.util.Comparator.comparing(
                        (List<AgentEvent> events) -> events.getFirst().timestamp()).reversed())
                .map(events -> InvocationTrace.of(sessionId, events))
                .toList();
    }

    /** 按 Invocation 返回单次执行的完整事件轨迹；无记录时返回 404。 */
    @GetMapping("/{invocationId}")
    public ResponseEntity<InvocationTrace> byInvocation(
            @PathVariable String invocationId, HttpServletRequest request) {
        List<AgentEvent> events = eventStore.findByInvocationId(invocationId);
        if (events.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        authorization.requireOwned(events.getFirst().sessionId(), request);
        return ResponseEntity.ok(InvocationTrace.of(events.getFirst().sessionId(), events));
    }

    private static AgentEventType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AgentEventType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 一次 Invocation 的事件轨迹。
     *
     * @param sessionId 所属会话
     * @param invocationId 本次执行标识
     * @param agentId 执行 Agent；取首个事件的归属
     * @param startedAt 首个事件时间
     * @param endedAt 末个事件时间
     * @param eventCount 事件总数
     * @param terminalType 终态事件类型；未收口时为空
     * @param events 按时间顺序排列的事件
     */
    public record InvocationTrace(
            String sessionId,
            String invocationId,
            String agentId,
            String startedAt,
            String endedAt,
            int eventCount,
            String terminalType,
            List<EventView> events) {

        static InvocationTrace of(String sessionId, List<AgentEvent> events) {
            AgentEvent first = events.getFirst();
            AgentEvent last = events.getLast();
            String terminal = events.stream()
                    .map(AgentEvent::type)
                    .filter(type -> type == AgentEventType.AGENT_COMPLETED
                            || type == AgentEventType.AGENT_FAILED)
                    .reduce((left, right) -> right)
                    .map(AgentEventType::name)
                    .orElse("");
            return new InvocationTrace(
                    sessionId,
                    first.invocationId(),
                    first.agentId(),
                    first.timestamp().toString(),
                    last.timestamp().toString(),
                    events.size(),
                    terminal,
                    events.stream().map(EventView::from).toList());
        }
    }

    /** 领域事件 REST 视图；时间统一为 ISO-8601 字符串。 */
    public record EventView(
            String eventId,
            String sessionId,
            String invocationId,
            String agentId,
            String timestamp,
            String type,
            String message,
            Map<String, Object> data,
            ActionsView actions) {

        static EventView from(AgentEvent event) {
            return new EventView(
                    event.eventId(),
                    event.sessionId(),
                    event.invocationId(),
                    event.agentId(),
                    event.timestamp().toString(),
                    event.type().name(),
                    event.message(),
                    event.data(),
                    ActionsView.from(event.actions()));
        }
    }

    /** 事件附带的状态变更指令视图。 */
    public record ActionsView(
            Map<String, Object> stateDelta,
            String transferToAgent,
            boolean endInvocation,
            boolean requireApproval) {

        static ActionsView from(EventActions actions) {
            EventActions value = actions == null ? EventActions.NONE : actions;
            return new ActionsView(
                    value.stateDelta(), value.transferToAgent(),
                    value.endInvocation(), value.requireApproval());
        }
    }
}
