package com.github.agentos.tool;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ToolDispatcher 生命周期测试。 */
class ToolDispatcherTest {

    @Test
    void beforeInterceptorCanShortCircuitExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        AgentTool tool = tool(call -> {
            executed.set(true);
            return ToolResult.success("tool");
        });
        ToolInterceptor interceptor = new ToolInterceptor() {
            @Override
            public ToolBeforeResult beforeExecute(
                    ToolCall call, ToolExecutionContext context) {
                return ToolBeforeResult.shortCircuit(ToolResult.success("cached"));
            }
        };

        ToolResult result = dispatcher(tool, interceptor).dispatch(
                new ToolCall("test", Map.of()), contextFactory());

        assertThat(result.output()).isEqualTo("cached");
        assertThat(executed).isFalse();
    }

    @Test
    void afterInterceptorCanModifyResult() {
        ToolInterceptor interceptor = new ToolInterceptor() {
            @Override
            public ToolResult afterExecute(
                    ToolCall call, ToolResult result, ToolExecutionContext context) {
                return ToolResult.success(result.output() + "-after");
            }
        };

        ToolResult result = dispatcher(tool(call -> ToolResult.success("tool")), interceptor)
                .dispatch(new ToolCall("test", Map.of()), contextFactory());

        assertThat(result.output()).isEqualTo("tool-after");
    }

    @Test
    void onErrorInterceptorConvertsThrownException() {
        ToolInterceptor interceptor = new ToolInterceptor() {
            @Override
            public ToolResult onError(
                    ToolCall call, Throwable error, ToolExecutionContext context) {
                return ToolResult.failure(ToolFailureType.TRANSIENT, "converted");
            }
        };

        ToolResult result = dispatcher(tool(call -> {
            throw new IllegalStateException("boom");
        }), interceptor).dispatch(new ToolCall("test", Map.of()), contextFactory());

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(result.error()).isEqualTo("converted");
    }

    @Test
    void sequentialModePreservesDeclaredOrder() {
        java.util.concurrent.CopyOnWriteArrayList<String> order =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentTool first = namedTool("first", true, call -> {
            order.add("first");
            return ToolResult.success("1");
        });
        AgentTool second = namedTool("second", true, call -> {
            order.add("second");
            return ToolResult.success("2");
        });
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(first, second)));

        List<ToolResult> results = dispatcher.dispatch(
                List.of(new ToolCall("first", Map.of()), new ToolCall("second", Map.of())),
                ToolExecutionMode.SEQUENTIAL, contextFactory());

        assertThat(order).containsExactly("first", "second");
        assertThat(results).extracting(ToolResult::output).containsExactly("1", "2");
    }

    @Test
    void parallelModeRunsOnlyExplicitlySafeToolsConcurrently() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ToolBehavior behavior = call -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            Thread.sleep(80);
            active.decrementAndGet();
            return ToolResult.success(call.toolName());
        };
        AgentTool first = namedTool("first", true, behavior);
        AgentTool second = namedTool("second", true, behavior);
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(first, second)));

        List<ToolResult> results = dispatcher.dispatch(
                List.of(new ToolCall("first", Map.of()), new ToolCall("second", Map.of())),
                ToolExecutionMode.PARALLEL, contextFactory());

        assertThat(maximum).hasValue(2);
        assertThat(results).extracting(ToolResult::output).containsExactly("first", "second");
    }

    @Test
    void parallelModeFallsBackToSequentialWhenToolIsNotSafe() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ToolBehavior behavior = call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(20);
            active.decrementAndGet();
            return ToolResult.success(call.toolName());
        };
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(
                namedTool("first", true, behavior), namedTool("writer", false, behavior))));

        dispatcher.dispatch(
                List.of(new ToolCall("first", Map.of()), new ToolCall("writer", Map.of())),
                ToolExecutionMode.PARALLEL, contextFactory());

        assertThat(maximum).hasValue(1);
    }

    private static ToolDispatcher dispatcher(AgentTool tool, ToolInterceptor interceptor) {
        return new ToolDispatcher(new ToolRegistry(List.of(tool)), List.of(interceptor));
    }

    private static ToolDispatcher.ToolExecutionContextFactory contextFactory() {
        return tool -> new ToolExecutionContext(
                AgentRequest.of("session-1", "test"), AgentContext.of("main-agent"),
                "plan-1", "step-1", AgentExecutionLimits.defaults(), Map.of(), tool);
    }

    private static AgentTool tool(ToolBehavior behavior) {
        return namedTool("test", false, behavior);
    }

    private static AgentTool namedTool(
            String name, boolean parallelSafe, ToolBehavior behavior) {
        return new AgentTool() {
            @Override
            public String name() { return name; }

            @Override
            public String description() { return "test tool"; }

            @Override
            public boolean parallelSafe() { return parallelSafe; }

            @Override
            public ToolResult execute(ToolCall call) throws Exception {
                return behavior.execute(call);
            }
        };
    }

    @FunctionalInterface
    private interface ToolBehavior {
        ToolResult execute(ToolCall call) throws Exception;
    }
}
