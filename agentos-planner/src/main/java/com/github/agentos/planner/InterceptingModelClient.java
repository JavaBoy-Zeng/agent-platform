package com.github.agentos.planner;

import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在协议无关的 ModelClient 外统一执行模型生命周期。 */
public final class InterceptingModelClient implements ModelClient {

    private final ModelClient delegate;
    private final List<ModelInterceptor> interceptors;

    /** 创建使用有序拦截器链的模型客户端。 */
    public InterceptingModelClient(ModelClient delegate, List<ModelInterceptor> interceptors) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.interceptors = List.copyOf(
                Objects.requireNonNull(interceptors, "interceptors must not be null"));
    }

    /** 执行 before、真实模型、逆序 after，并统一发布模型领域事件。 */
    @Override
    public ModelPlan generatePlan(PlanningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ModelCallContext context = new ModelCallContext(request, Instant.now());
        PlanningRequest current = request;
        publish(request, AgentEventType.MODEL_CALL_STARTED, "模型调用开始", Map.of());
        if (request.agentContext().invocation() != null) {
            request.agentContext().invocation().incrementModelCalls();
        }
        try {
            for (ModelInterceptor interceptor : interceptors) {
                current = Objects.requireNonNull(
                        interceptor.beforeCall(current, context),
                        "model interceptor returned null request");
            }
            ModelPlan response = Objects.requireNonNull(
                    delegate.generatePlan(current), "model client returned null response");
            List<ModelInterceptor> reverse = new ArrayList<>(interceptors);
            Collections.reverse(reverse);
            for (ModelInterceptor interceptor : reverse) {
                response = Objects.requireNonNull(
                        interceptor.afterCall(response, context),
                        "model interceptor returned null response");
            }
            publish(request, AgentEventType.MODEL_CALL_COMPLETED, "模型调用完成",
                    Map.of("outcome", response.outcome().name()));
            return response;
        } catch (Throwable error) {
            List<ModelInterceptor> reverse = new ArrayList<>(interceptors);
            Collections.reverse(reverse);
            for (ModelInterceptor interceptor : reverse) {
                try {
                    ModelPlan fallback = interceptor.onError(error, context);
                    if (fallback != null) {
                        publish(request, AgentEventType.MODEL_CALL_COMPLETED,
                                "模型调用已由 fallback 恢复", Map.of("fallback", true));
                        return fallback;
                    }
                } catch (RuntimeException ignored) {
                    // 错误拦截器失败不覆盖原始模型异常。
                }
            }
            publish(request, AgentEventType.MODEL_CALL_FAILED, errorMessage(error),
                    Map.of("errorType", error.getClass().getSimpleName()));
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("model call failed", error);
        }
    }

    private static void publish(
            PlanningRequest request, AgentEventType type, String message,
            Map<String, Object> data) {
        if (request.agentContext().invocation() == null) {
            return;
        }
        try {
            request.agentContext().eventPublisher().publish(
                    DefaultAgentEvent.of(request.agentContext(), type, message, data));
        } catch (RuntimeException ignored) {
            // 观察端不得破坏模型调用。
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
