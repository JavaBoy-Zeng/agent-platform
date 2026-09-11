package com.github.agentos.agent.specialist;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.agent.routing.*;
import com.github.agentos.kernel.*;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ToolProviderAgentTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void limitsManifestAndExecutionToInvocationProvider(boolean allowed) {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        AgentTool weather = new AgentTool() {
            public String name() { return "weather"; }
            public String description() { return "weather by city"; }
            public ToolResult execute(ToolContext context, ToolCall call) {
                assertThat(call.arguments()).containsEntry("city", "杭州");
                calls.incrementAndGet();
                return ToolResult.success("晴");
            }
        };
        ChatClient chat = new ChatClient() {
            public String chat(String session, LlmRequest request) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, LlmRequest request, Consumer<String> delta) {
                assertThat(request.tools()).extracting(tool -> tool.name()).containsExactly("weather");
                return rounds.incrementAndGet() == 1 ? ToolCallResponse.call(
                        new ToolCall(allowed ? "weather" : "unlisted", Map.of("city", "杭州")), "c1", null)
                        : ToolCallResponse.answer("杭州晴", null);
            }
        };
        var agent = new ToolProviderAgent("weather-agent", "weather", "查询天气",
                Set.of(AgentCapability.WEB_RESEARCH), Set.of(RouteScope.EXTERNAL_WORLD),
                context -> {
                    assertThat(context.agentId()).isEqualTo("weather-agent");
                    return List.of(weather);
                }, chat);
        agent.configureExecution(new ToolDispatcher(new ToolRegistry(List.of(weather))),
                ContinuationStore.NOOP, AgentExecutionLimits.defaults());
        var result = agent.run(AgentRequest.of("s1", "杭州天气"),
                InvocationContext.of("weather-agent"), AgentState.ready().startNextIteration());
        assertThat(result.status()).isEqualTo(allowed ? AgentState.Status.COMPLETED : AgentState.Status.FAILED);
        assertThat(calls).hasValue(allowed ? 1 : 0);
        assertThat(agent.accepts(AgentRequest.of("s1", "tools"), new SupervisorRouteDecision(
                "test", RouteScope.LOCAL_RUNTIME, Set.of(AgentCapability.WEB_RESEARCH),
                "weather-agent", 1, "test", "")).accepted()).isFalse();
    }
}
