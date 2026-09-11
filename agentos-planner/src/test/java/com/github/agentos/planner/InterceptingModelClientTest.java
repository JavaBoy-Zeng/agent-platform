package com.github.agentos.planner;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.memory.MemoryContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型拦截器生命周期测试。 */
class InterceptingModelClientTest {

    @Test
    void invokesBeforeAndAfterInNestedOrder() {
        List<String> calls = new ArrayList<>();
        ModelInterceptor first = interceptor("first", calls);
        ModelInterceptor second = interceptor("second", calls);
        ModelClient client = new InterceptingModelClient(request -> {
            calls.add("model");
            return complete("answer");
        }, List.of(first, second));

        ModelPlan result = client.generatePlan(request());

        assertThat(result.finalAnswer()).isEqualTo("answer");
        assertThat(calls).containsExactly(
                "first-before", "second-before", "model", "second-after", "first-after");
    }

    @Test
    void errorInterceptorCanProvideFallback() {
        ModelInterceptor fallback = new ModelInterceptor() {
            @Override
            public ModelPlan onError(Throwable error, ModelCallContext context) {
                return complete("fallback");
            }
        };
        ModelClient client = new InterceptingModelClient(
                request -> { throw new IllegalStateException("unavailable"); },
                List.of(fallback));

        assertThat(client.generatePlan(request()).finalAnswer()).isEqualTo("fallback");
    }

    private static ModelInterceptor interceptor(String name, List<String> calls) {
        return new ModelInterceptor() {
            @Override
            public PlanningRequest beforeCall(
                    PlanningRequest request, ModelCallContext context) {
                calls.add(name + "-before");
                return request;
            }

            @Override
            public ModelPlan afterCall(ModelPlan response, ModelCallContext context) {
                calls.add(name + "-after");
                return response;
            }
        };
    }

    private static PlanningRequest request() {
        return new PlanningRequest(
                AgentRequest.of("session-1", "hello"), InvocationContext.of("plan-execute-agent"),
                MemoryContext.empty(false), null, null, List.of(), 1);
    }

    private static ModelPlan complete(String answer) {
        return new ModelPlan(
                PlanType.EXECUTION, PlanOutcome.COMPLETE, "answer",
                List.of(), answer);
    }
}
