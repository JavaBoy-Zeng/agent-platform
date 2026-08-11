package com.github.agentos.server;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "agentos.memory.mode=memory")
@Import(AgentRuntimeIntegrationTest.ScriptedModelConfiguration.class)
class AgentRuntimeIntegrationTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private MemoryService memoryService;

    @Test
    void replansAfterExecutionThenFinalizesAndCapturesOnlyCompletedTurn() {
        AgentRequest request = AgentRequest.of("session-1", "I prefer Java");
        AgentContext context = AgentContext.of("main-agent");

        List<AgentRunEvent> events = new CopyOnWriteArrayList<>();
        AgentState result = runtime.run(request, context, events::add);

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
