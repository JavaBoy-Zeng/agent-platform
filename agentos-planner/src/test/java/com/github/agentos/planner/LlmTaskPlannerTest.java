package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.tool.EchoTool;
import com.github.agentos.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTaskPlannerTest {

    @Test
    void recallsLayeredMemoryBeforeCallingTheModel() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));
        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");

        try (MemoryService memoryService = MemoryService.inMemory()) {
            memoryService.capture(CompletedTurn.success(
                    scope,
                    "I prefer concise answers and must use Java.",
                    "Understood.",
                    List.of("preference saved")));
            assertThat(memoryService.awaitIdle(Duration.ofSeconds(2))).isTrue();

            AtomicReference<PlanningRequest> capturedRequest = new AtomicReference<>();
            ModelClient modelClient = request -> {
                capturedRequest.set(request);
                return new ModelPlan(
                        "Reply to the user",
                        List.of(new ModelPlan.Step(
                                "step-1",
                                "Echo input",
                                "echo",
                                Map.of("message", request.userInput()))));
            };
            LlmTaskPlanner planner = new LlmTaskPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5));
            AgentContext context = AgentContext.of("main-agent", "session-1", "hello");

            Plan plan = planner.createPlan(context);

            assertThat(plan.objective()).isEqualTo("Reply to the user");
            PlanningRequest request = capturedRequest.get();
            assertThat(request.agentContext()).isEqualTo(context);
            assertThat(request.memoryContext().recentTurns()).hasSize(1);
            assertThat(request.memoryContext().atomicMemories()).isNotEmpty();
            assertThat(request.memoryContext().optionalProfile()).isPresent();
            assertThat(request.memoryContext().formattedContext())
                    .contains("L0 Recent completed turns", "<memory_context>");
            assertThat(request.maxSteps()).isEqualTo(5);
            assertThat(request.availableTools()).singleElement().satisfies(tool ->
                    assertThat(tool.name()).isEqualTo("echo"));
        }
    }
}
