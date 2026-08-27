package com.github.agentos.server;

import com.github.agentos.agent.routing.IntentClassification;
import com.github.agentos.agent.routing.IntentClassifier;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.ModelClient;
import com.github.agentos.planner.ModelPlan;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "agentos.memory.mode=memory")
@Import(AgentRunnerIntegrationTest.ScriptedModelConfiguration.class)
class AgentRunnerIntegrationTest {

    @Autowired
    private AgentRunner runner;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private AgentEventStore eventStore;

    /**
     * 强制让路由层走 fallback，专注于 Runner + MainAgent + 模型脚本之间的协作。
     *
     * <p>输入 {@code "I prefer Java"}（12 字符、无工具关键词）默认会被
     * {@code HeuristicIntentClassifier} 短路返回 canned answer，从而绕过 LLM 调用；
     * 这里 mock 出固定的 fallback，让既有对完整 plan-and-execute 周期的断言仍然成立。</p>
     *
     * <p>同时 mock {@link ChatClient} 让 {@link com.github.agentos.agent.specialist.SupervisorAgent}
     * 始终回退到 MainAgent，跳过 LLM 分类。</p>
     */
    @MockitoBean
    private IntentClassifier intentClassifier;

    @MockitoBean
    private ChatClient chatClient;

    @Test
    void replansAfterExecutionThenFinalizesAndCapturesOnlyCompletedTurn() {
        org.mockito.Mockito.when(intentClassifier.classify(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(IntentClassification.fallback("integration-test"));
        org.mockito.Mockito.when(chatClient.chat(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("main-agent");
        org.mockito.Mockito.when(chatClient.chatStream(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(2);
                    onDelta.accept("I prefer ");
                    onDelta.accept("Java");
                    return new ChatClient.ChatResponse("I prefer Java", null);
                });
        AgentRequest request = AgentRequest.of("session-1", "I prefer Java");
        InvocationContext context = InvocationContext.of("main-agent");

        List<AgentRunEvent> events = new CopyOnWriteArrayList<>();
        AgentState result = runner.run(request, context, events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("I prefer Java");
        assertThat(events).extracting(AgentRunEvent::type).containsSubsequence(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.PLAN_CREATED,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.REPLAN,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.REPLAN)
                .hasSize(1);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.DECISION)
                .extracting(event -> event.data().get("outcome"))
                .containsExactly("REPLAN", "COMPLETE");
        String invocationId = runner.latestInvocation("session-1").orElseThrow().invocationId();
        assertThat(eventStore.findByInvocationId(invocationId))
                .extracting(AgentEvent::type)
                .containsSubsequence(
                        AgentEventType.AGENT_STARTED,
                        AgentEventType.PLAN_CREATED,
                        AgentEventType.STEP_STARTED,
                        AgentEventType.TOOL_CALL_STARTED,
                        AgentEventType.TOOL_CALL_COMPLETED,
                        AgentEventType.STEP_COMPLETED,
                        AgentEventType.AGENT_COMPLETED);
        assertThat(eventStore.findByInvocationId(invocationId))
                .extracting(AgentEvent::invocationId)
                .containsOnly(invocationId);
        assertThat(runner.invocation(invocationId).orElseThrow()).satisfies(invocation -> {
            // 初始规划 + 两次决策规划 + 最终回答 SSE。
            assertThat(invocation.modelCalls()).isEqualTo(4);
            assertThat(invocation.toolCalls()).isEqualTo(2);
            assertThat(invocation.replans()).isEqualTo(1);
            assertThat(invocation.steps()).isEqualTo(2);
        });
        assertThat(memoryService.awaitIdle(Duration.ofSeconds(2))).isTrue();

        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");
        assertThat(memoryService.recentTurns(scope, 10)).singleElement().satisfies(turn -> {
            assertThat(turn.userInput()).isEqualTo("I prefer Java");
            assertThat(turn.assistantOutput()).isEqualTo("I prefer Java");
            assertThat(turn.toolOutputs()).containsExactly(
                    "echo: discovered Maven workspace",
                    "echo: I prefer Java");
        });
        assertThat(memoryService.atomicMemories(scope)).isNotEmpty();
        assertThat(memoryService.scenarios(scope)).isNotEmpty();
        assertThat(memoryService.profile(scope)).isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ModelClient scriptedModelClient() {
            return request -> {
                if (!request.replanning()) {
                    return new ModelPlan(
                            PlanType.DISCOVERY,
                            PlanOutcome.CONTINUE,
                            "Discover the environment",
                            List.of(new ModelPlan.Step(
                                    "step-1",
                                    "Produce a deterministic discovery observation",
                                    false,
                                    "echo",
                                    Map.of("message", "discovered Maven workspace"))),
                            null);
                }
                if (request.executionSnapshot().reason()
                        == com.github.agentos.planner.ReplanReason.DISCOVERY_COMPLETED) {
                    return new ModelPlan(
                            PlanType.EXECUTION,
                            PlanOutcome.CONTINUE,
                            "Execute using discovered facts",
                            List.of(new ModelPlan.Step(
                                    "step-2",
                                    "Produce the requested result",
                                    false,
                                    "echo",
                                    Map.of("message", request.agentRequest().objective()))),
                            null);
                }
                return new ModelPlan(
                        PlanType.EXECUTION,
                        PlanOutcome.COMPLETE,
                        "Answer from observations",
                        List.of(),
                        request.executionSnapshot().lastResult().output());
            };
        }
    }
}
