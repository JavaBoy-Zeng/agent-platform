package com.github.agentos.kernel.trace;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.AgentPlugin;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.ModelUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可观测性插件：从 Agent 生命周期事件构建 {@link Span} 并写入 {@link TraceStore}。
 *
 * <p>实现 {@link AgentPlugin}，自动获得 beforeRun/afterRun/onRunError/onModelUsage/onEvent
 * 全部钩子。通过 per-traceId 的栈追踪嵌套 Span：</p>
 * <ul>
 *   <li>{@code beforeRun} → 开启根 Span（ROOT，parentSpanId 为空）</li>
 *   <li>{@code onEvent(*_STARTED)} → 开启子 Span（MODEL/TOOL/STEP）</li>
 *   <li>{@code onEvent(*_COMPLETED/FAILED)} → 关闭栈顶 Span</li>
 *   <li>{@code afterRun} → 关闭根 Span，状态取自 {@link AgentState.Status}</li>
 *   <li>{@code onRunError} → 在根 Span 记录异常</li>
 *   <li>{@code onModelUsage} → 在栈顶 MODEL Span 记录 token 用量</li>
 * </ul>
 *
 * <p>所有操作 fail-soft：观察端异常被捕获并记录，绝不破坏 Agent 执行链。</p>
 */
public final class TraceRecorder implements AgentPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceRecorder.class);

    private final TraceStore traceStore;

    /** traceId → 开放 Span 栈；栈底为根 Span。 */
    private final Map<String, Deque<OpenSpan>> openSpans = new ConcurrentHashMap<>();

    /** traceId → 根 OpenSpan（与栈底同步，便于 afterRun 定位）。 */
    private final Map<String, OpenSpan> rootSpans = new ConcurrentHashMap<>();

    public TraceRecorder(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @Override
    public String name() {
        return "TraceRecorder";
    }

    @Override
    public void beforeRun(AgentRequest request, InvocationContext context) {
        try {
            String traceId = context.invocationId();
            if (traceId == null || traceId.isEmpty()) {
                return;
            }
            OpenSpan root = new OpenSpan(
                    UUID.randomUUID().toString(),
                    "",
                    "agent-run",
                    Span.Kind.ROOT,
                    Instant.now());
            root.attributes.putAll(Map.of(
                    "sessionId", context.sessionId(),
                    "agentId", context.agentId(),
                    "taskId", context.taskId(),
                    "objective", request.objective()));
            openSpans.computeIfAbsent(traceId, k -> new ArrayDeque<>()).push(root);
            rootSpans.put(traceId, root);
        } catch (RuntimeException exception) {
            LOGGER.warn("[trace] beforeRun failed", exception);
        }
    }

    @Override
    public void afterRun(AgentRequest request, InvocationContext context, AgentState result) {
        try {
            String traceId = context.invocationId();
            if (traceId == null || traceId.isEmpty()) {
                return;
            }
            OpenSpan root = rootSpans.remove(traceId);
            if (root == null) {
                return;
            }
            Span.Status status = mapStatus(result.status());
            root.attributes.put("finalStatus", result.status().name());
            if (!result.output().isEmpty()) {
                root.attributes.put("output", truncate(result.output(), 500));
            }
            if (!result.error().isEmpty()) {
                root.attributes.put("error", truncate(result.error(), 500));
            }
            closeSpan(traceId, root, status);
            openSpans.remove(traceId);
        } catch (RuntimeException exception) {
            LOGGER.warn("[trace] afterRun failed", exception);
        }
    }

    @Override
    public void onRunError(AgentRequest request, InvocationContext context, Exception error) {
        try {
            String traceId = context.invocationId();
            if (traceId == null || traceId.isEmpty()) {
                return;
            }
            OpenSpan root = rootSpans.get(traceId);
            if (root != null) {
                root.attributes.put("runError", truncate(error.getMessage(), 500));
                root.attributes.put("errorType", error.getClass().getSimpleName());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[trace] onRunError failed", exception);
        }
    }

    @Override
    public void onModelUsage(String sessionId, ModelUsage usage) {
        try {
            // 在最近的 MODEL Span 上记录 token 用量
            for (Deque<OpenSpan> stack : openSpans.values()) {
                OpenSpan top = stack.peek();
                if (top != null && top.kind == Span.Kind.MODEL) {
                    top.attributes.put("model", usage.model());
                    top.attributes.put("promptTokens", usage.promptTokens());
                    top.attributes.put("completionTokens", usage.completionTokens());
                    top.attributes.put("totalTokens", usage.totalTokens());
                    return;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[trace] onModelUsage failed", exception);
        }
    }

    @Override
    public void onEvent(AgentEvent event) {
        try {
            String traceId = event.invocationId();
            if (traceId == null || traceId.isEmpty()) {
                return;
            }
            Deque<OpenSpan> stack = openSpans.get(traceId);
            if (stack == null || stack.isEmpty()) {
                return;
            }
            switch (event.type()) {
                case MODEL_CALL_STARTED -> pushChildSpan(stack, traceId, "model-call", Span.Kind.MODEL, event);
                case TOOL_CALL_STARTED -> pushChildSpan(stack, traceId, "tool-call", Span.Kind.TOOL, event);
                case STEP_STARTED -> pushChildSpan(stack, traceId, "step", Span.Kind.STEP, event);
                case MODEL_CALL_COMPLETED, MODEL_CALL_FAILED ->
                        popAndCloseSpan(stack, traceId, event, Span.Kind.MODEL);
                case TOOL_CALL_COMPLETED, TOOL_CALL_FAILED ->
                        popAndCloseSpan(stack, traceId, event, Span.Kind.TOOL);
                case STEP_COMPLETED, STEP_FAILED ->
                        popAndCloseSpan(stack, traceId, event, Span.Kind.STEP);
                case PLAN_CREATED, REPLAN_STARTED -> recordEventOnTop(stack, "plan", event);
                case HUMAN_ACTION_REQUIRED -> recordEventOnTop(stack, "await-approval", event);
                case HUMAN_ACTION_RESOLVED -> recordEventOnTop(stack, "approval-resolved", event);
                case AGENT_STARTED, AGENT_COMPLETED, AGENT_FAILED -> {
                    // 根 Span 由 beforeRun/afterRun 管理，事件作为属性记录。
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[trace] onEvent failed for type={}", event.type(), exception);
        }
    }

    /** 开启子 Span，parent 为当前栈顶。 */
    private void pushChildSpan(Deque<OpenSpan> stack, String traceId,
                               String name, Span.Kind kind, AgentEvent event) {
        OpenSpan parent = stack.peek();
        String parentSpanId = parent != null ? parent.spanId : "";
        OpenSpan span = new OpenSpan(
                UUID.randomUUID().toString(),
                parentSpanId,
                deriveSpanName(name, event),
                kind,
                Instant.now());
        span.attributes.putAll(event.data());
        span.attributes.put("eventId", event.eventId());
        span.attributes.put("message", event.message() == null ? "" : event.message());
        stack.push(span);
    }

    /** 关闭栈顶匹配 kind 的 Span 并写入 Store。 */
    private void popAndCloseSpan(Deque<OpenSpan> stack, String traceId,
                                 AgentEvent event, Span.Kind expectedKind) {
        OpenSpan top = stack.peek();
        if (top == null || top.kind != expectedKind) {
            // 类型不匹配或栈空，尝试关闭栈顶（容错）
            if (top == null || top.kind == Span.Kind.ROOT) {
                return;
            }
        }
        OpenSpan popped = stack.pop();
        popped.attributes.putAll(event.data());
        Span.Status status = switch (event.type()) {
            case MODEL_CALL_FAILED, TOOL_CALL_FAILED, STEP_FAILED -> Span.Status.ERROR;
            case MODEL_CALL_COMPLETED, TOOL_CALL_COMPLETED, STEP_COMPLETED -> Span.Status.OK;
            default -> Span.Status.OK;
        };
        closeSpan(traceId, popped, status);
    }

    /** 在栈顶 Span 记录一个轻量事件。 */
    private void recordEventOnTop(Deque<OpenSpan> stack, String name, AgentEvent event) {
        OpenSpan top = stack.peek();
        if (top != null) {
            top.events.put(event.timestamp().toString(), name + ": " + event.message());
        }
    }

    /** 从 OpenSpan 构建最终 Span 并写入 Store。 */
    private void closeSpan(String traceId, OpenSpan open, Span.Status status) {
        Span span = new Span(
                traceId,
                open.spanId,
                open.parentSpanId,
                open.name,
                open.kind,
                open.start,
                Instant.now(),
                status,
                Map.copyOf(open.attributes),
                Map.copyOf(open.events));
        traceStore.append(span);
    }

    /** 根据 AgentState.Status 映射 Span.Status。 */
    private Span.Status mapStatus(AgentState.Status status) {
        return switch (status) {
            case COMPLETED -> Span.Status.OK;
            case FAILED -> Span.Status.ERROR;
            case CANCELLED -> Span.Status.CANCELLED;
            default -> Span.Status.OK;
        };
    }

    /** 从事件 data 中提取工具名或模型名作为 Span 名称后缀。 */
    private String deriveSpanName(String prefix, AgentEvent event) {
        Map<String, Object> data = event.data();
        if (data != null) {
            Object toolName = data.get("toolName");
            if (toolName != null && !toolName.toString().isEmpty()) {
                return prefix + ":" + toolName;
            }
        }
        return prefix;
    }

    /** 截断长字符串，避免 Span 属性过大。 */
    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 可变的开放 Span 持有者，关闭时构建不可变 Span。 */
    private static final class OpenSpan {
        final String spanId;
        final String parentSpanId;
        final String name;
        final Span.Kind kind;
        final Instant start;
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final Map<String, Object> events = new ConcurrentHashMap<>();

        OpenSpan(String spanId, String parentSpanId, String name,
                 Span.Kind kind, Instant start) {
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.name = name;
            this.kind = kind;
            this.start = start;
        }
    }
}
