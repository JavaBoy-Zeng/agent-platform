package com.github.agentos.tool.runtime;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.EventActions;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolExecutionMode;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 工具注册表解析与完整生命周期的统一执行边界。
 *
 * <p>Dispatcher 统一处理未知工具、前置短路、工具异常、结果后处理和领域事件发布，
 * 并把 {@link ToolContext} 传递给工具，使工具能访问 Invocation、会话状态与预算。</p>
 */
public final class ToolDispatcher {

    private final ToolRegistry registry;
    private final List<ToolInterceptor> interceptors;

    /** 创建不带拦截器的 Dispatcher。 */
    public ToolDispatcher(ToolRegistry registry) {
        this(registry, List.of());
    }

    /** 创建使用有序拦截器链的 Dispatcher。 */
    public ToolDispatcher(ToolRegistry registry, List<ToolInterceptor> interceptors) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.interceptors = List.copyOf(
                Objects.requireNonNull(interceptors, "interceptors must not be null"));
    }

    /** 执行一次完整工具生命周期并保证异常不会越过工具边界。 */
    public ToolResult dispatch(ToolCall call, ToolContextFactory contextFactory) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        AgentTool tool = registry.find(call.toolName()).orElse(null);
        if (tool == null) {
            return ToolResult.failure(ToolFailureType.UNKNOWN, "unknown tool: " + call.toolName());
        }
        ToolContext context = contextFactory.create(tool);
        publish(context, AgentEventType.TOOL_CALL_STARTED, "工具调用开始", call, null, EventActions.NONE);
        if (context.invocation().cancellation().isCancelled()) {
            // 已取消的运行不再启动工具；发布失败事件保持事件流成对出现。
            String reason = context.invocation().cancellation().reason()
                    .map(text -> ": " + text).orElse("");
            return publishResult(context, call, ToolResult.failure(
                    ToolFailureType.CANCELLED, "run cancelled" + reason));
        }
        try {
            for (ToolInterceptor interceptor : interceptors) {
                ToolBeforeResult before = Objects.requireNonNull(
                        interceptor.beforeExecute(call, context),
                        "tool interceptor returned null before result");
                if (!before.proceed()) {
                    return publishResult(context, call, before.result());
                }
            }
            ToolResult result = Objects.requireNonNull(
                    tool.execute(context, call), "tool returned null result");
            for (ToolInterceptor interceptor : interceptors) {
                result = Objects.requireNonNull(
                        interceptor.afterExecute(call, result, context),
                        "tool interceptor returned null after result");
            }
            return publishResult(context, call, result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return publishResult(context, call,
                    ToolResult.failure(ToolFailureType.TRANSIENT, "tool execution interrupted"));
        } catch (Throwable error) {
            ToolResult result = null;
            for (ToolInterceptor interceptor : interceptors) {
                try {
                    result = Objects.requireNonNull(
                            interceptor.onError(call, error, context),
                            "tool interceptor returned null error result");
                } catch (RuntimeException ignored) {
                    // 继续尝试后续错误拦截器。
                }
            }
            if (result == null) {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                result = ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, message);
            }
            return publishResult(context, call, result);
        }
    }

    /**
     * 按指定模式执行一组调用并保持结果与输入位置一一对应。
     *
     * <p>当任一工具未知或没有显式声明 {@link AgentTool#parallelSafe()} 时，PARALLEL
     * 会安全降级为顺序执行，避免意外并发写操作。</p>
     */
    public List<ToolResult> dispatch(
            List<ToolCall> calls,
            ToolExecutionMode mode,
            ToolContextFactory contextFactory) {
        List<ToolCall> safeCalls = List.copyOf(
                Objects.requireNonNull(calls, "calls must not be null"));
        Objects.requireNonNull(mode, "mode must not be null");
        if (mode == ToolExecutionMode.SEQUENTIAL || !allParallelSafe(safeCalls)) {
            return safeCalls.stream().map(call -> dispatch(call, contextFactory)).toList();
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ToolResult>> futures = safeCalls.stream()
                    .map(call -> CompletableFuture.supplyAsync(
                            () -> dispatch(call, contextFactory), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private boolean allParallelSafe(List<ToolCall> calls) {
        return !calls.isEmpty() && calls.stream().allMatch(call -> registry.find(call.toolName())
                .map(AgentTool::parallelSafe).orElse(false));
    }

    private ToolResult publishResult(
            ToolContext context, ToolCall call, ToolResult result) {
        Map<String, Object> stateDelta = result.actions().stateDelta();
        EventActions eventActions = stateDelta.isEmpty()
                ? EventActions.NONE
                : EventActions.stateDelta(stateDelta);
        publish(context,
                result.success() ? AgentEventType.TOOL_CALL_COMPLETED
                        : AgentEventType.TOOL_CALL_FAILED,
                result.success() ? "工具调用完成" : result.error(), call, result, eventActions);
        return result;
    }

    private static void publish(
            ToolContext context, AgentEventType type, String message,
            ToolCall call, ToolResult result, EventActions eventActions) {
        if (context.invocation().invocation() == null) {
            return;
        }
        // 事件数据必须携带真实调用参数与结果摘要，供前端展示和会话历史重建执行记录。
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("planId", context.planId());
        data.put("stepId", context.stepId());
        data.put("toolName", call.toolName());
        data.put("arguments", ToolEventSupport.abbreviateArguments(call));
        if (result != null) {
            data.put("success", result.success());
            data.put("failureType", result.failureType().name());
            data.put("summary", ToolEventSupport.summarize(result));
        }
        AgentEvent event = DefaultAgentEvent.of(
                context.invocation(), type, message, data, eventActions);
        try {
            context.invocation().eventPublisher().publish(event);
        } catch (RuntimeException ignored) {
            // 观察端不得中断工具执行。
        }
    }

    /** 在工具解析完成后创建包含真实工具对象的执行上下文。 */
    @FunctionalInterface
    public interface ToolContextFactory {
        ToolContext create(AgentTool tool);
    }
}
