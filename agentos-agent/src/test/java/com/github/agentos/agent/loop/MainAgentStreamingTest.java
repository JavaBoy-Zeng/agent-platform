package com.github.agentos.agent.loop;

import com.github.agentos.agent.finalize.ModelStreamingAgentFinalizer;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.DefaultFailureClassifier;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.PlanOrigin;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MainAgentStreamingTest {

    @Test
    void forwardsProviderDeltasWithoutRechunkingAfterTheFullAnswer() {
        String firstProviderDelta = "A".repeat(200);
        AtomicBoolean streamReturned = new AtomicBoolean();
        ChatClient client = new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                throw new AssertionError("non-streaming chat must not be used");
            }

            @Override
            public ChatResponse chatStream(
                    String sessionId, LlmRequest request, Consumer<String> onDelta) {
                onDelta.accept(firstProviderDelta);
                onDelta.accept("B");
                streamReturned.set(true);
                return new ChatResponse(firstProviderDelta + "B", null);
            }
        };
        AgentPlanner planner = new AgentPlanner() {
            @Override
            public AgentPlan createPlan(AgentRequest request, InvocationContext context) {
                return AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.COMPLETE,
                        "answer objective", List.of(), "候选答案");
            }

            @Override
            public AgentPlan replan(
                    AgentRequest request,
                    InvocationContext context,
                    AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                throw new AssertionError("replan must not be used");
            }
        };
        List<AgentRunEvent> events = new ArrayList<>();

        try (MemoryService memory = MemoryService.inMemory()) {
            MainAgent agent = new MainAgent(
                    planner,
                    new PlanExecutor(
                            new ToolDispatcher(new ToolRegistry(List.of()), List.of()),
                            new DefaultFailureClassifier()),
                    memory,
                    new ModelStreamingAgentFinalizer(client),
                    new AgentExecutionLimits(3, 10, 10, 3));
            AgentRunner runner = new AgentRunner(agent);

            AgentRunner.AgentRunResult result = runner.runDetailed(
                    AgentRequest.of("stream-main", "完成复杂任务"),
                    InvocationContext.of("main-agent"),
                    event -> {
                        if (event.type() == AgentRunEvent.Type.OUTPUT_DELTA) {
                            assertThat(streamReturned).isFalse();
                        }
                        events.add(event);
                    },
                    AgentEventPublisher.NOOP);

            assertThat(result.state().output()).isEqualTo(firstProviderDelta + "B");
            assertThat(runner.invocation(result.invocationId()).orElseThrow().modelCalls())
                    .isEqualTo(2);
        }

        assertThat(events)
                .filteredOn(event -> event.type() == AgentRunEvent.Type.OUTPUT_DELTA)
                .extracting(AgentRunEvent::message)
                .containsExactly(firstProviderDelta, "B");
        assertThat(events)
                .filteredOn(event -> event.type() == AgentRunEvent.Type.OUTPUT_DELTA)
                .allSatisfy(event -> assertThat(event.data())
                        .containsEntry("source", "model-sse"));
    }
}
