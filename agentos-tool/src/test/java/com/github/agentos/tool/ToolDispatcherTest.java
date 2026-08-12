package com.github.agentos.tool;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static ToolDispatcher dispatcher(AgentTool tool, ToolInterceptor interceptor) {
        return new ToolDispatcher(new ToolRegistry(List.of(tool)), List.of(interceptor));
    }

    private static ToolDispatcher.ToolExecutionContextFactory contextFactory() {
        return tool -> new ToolExecutionContext(
                AgentRequest.of("session-1", "test"), AgentContext.of("main-agent"),
                "plan-1", "step-1", AgentExecutionLimits.defaults(), Map.of(), tool);
    }

    private static AgentTool tool(ToolBehavior behavior) {
        return new AgentTool() {
            @Override
            public String name() { return "test"; }

            @Override
            public String description() { return "test tool"; }

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
