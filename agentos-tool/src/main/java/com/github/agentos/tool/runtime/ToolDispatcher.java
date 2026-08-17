package com.github.agentos.tool.runtime;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
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
 * <p>Dispatcher 统一处理未知工具、前置短路、工具异常、结果后处理和领域事件发布。</p>
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
    public ToolResult dispatch(ToolCall call, ToolExecutionContextFactory contextFactory) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(contextFactory, "contextFactory must not be null");
        AgentTool tool = registry.find(call.toolName()).orElse(null);
        if (tool == null) {
            return ToolResult.failure(ToolFailureType.UNKNOWN, "unknown tool: " + call.toolName());
        }
        ToolExecutionContext context = contextFactory.create(tool);
        publish(context, AgentEventType.TOOL_CALL_STARTED, "工具调用开始", call, null);
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
                    tool.execute(call), "tool returned null result");
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
            ToolExecutionContextFactory contextFactory) {
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
            ToolExecutionContext context, ToolCall call, ToolResult result) {
        publish(context,
                result.success() ? AgentEventType.TOOL_CALL_COMPLETED
                        : AgentEventType.TOOL_CALL_FAILED,
                result.success() ? "工具调用完成" : result.error(), call, result);
        return result;
    }

    private static void publish(
            ToolExecutionContext context, AgentEventType type, String message,
            ToolCall call, ToolResult result) {
        if (context.agentContext().invocation() == null) {
            return;
        }
        Map<String, Object> data = result == null
                ? Map.of("planId", context.planId(), "stepId", context.stepId(),
                        "toolName", call.toolName())
                : Map.of("planId", context.planId(), "stepId", context.stepId(),
                        "toolName", call.toolName(), "success", result.success(),
                        "failureType", result.failureType().name());
        AgentEvent event = DefaultAgentEvent.of(context.agentContext(), type, message, data);
        try {
            context.agentContext().eventPublisher().publish(event);
        } catch (RuntimeException ignored) {
            // 观察端不得中断工具执行。
        }
    }

    /** 在工具解析完成后创建包含真实工具对象的执行上下文。 */
    @FunctionalInterface
    public interface ToolExecutionContextFactory {
        ToolExecutionContext create(AgentTool tool);
    }
}
