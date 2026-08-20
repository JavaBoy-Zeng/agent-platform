package com.github.agentos.planner;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.other.EchoTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAgentPlannerTest {

    @Test
    void promotesDiscoveryPlanWithSideEffectingToolToExecution() {
        AgentTool writeTool = new AgentTool() {
            @Override public String name() { return "write"; }
            @Override public String description() { return "write a resource"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("written");
            }
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(writeTool));

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.DISCOVERY,
                    PlanOutcome.CONTINUE,
                    "Write the requested resource",
                    List.of(new ModelPlan.Step(
                            "step-1", "Write it", false, "write", Map.of())),
                    null);
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5),
                    new AgentExecutionLimits(3, 30, 30, 6));

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of("session-1", "write it"),
                    InvocationContext.of("main-agent"));

            assertThat(plan.type()).isEqualTo(PlanType.EXECUTION);
            assertThat(plan.steps()).singleElement().satisfies(step ->
                    assertThat(step.toolCalls().getFirst().toolName()).isEqualTo("write"));
        }
    }

    @Test
    void recallsMemoryAndInjectsRuntimeOnlyPlanMetadata() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));
        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");

        try (MemoryService memoryService = MemoryService.inMemory()) {
            memoryService.capture(CompletedTurn.success(
                    scope,
                    "I prefer concise Java answers.",
                    "Understood.",
                    List.of("preference saved")));
            assertThat(memoryService.awaitIdle(Duration.ofSeconds(2))).isTrue();

            AtomicReference<PlanningRequest> capturedRequest = new AtomicReference<>();
            ModelClient modelClient = request -> {
                capturedRequest.set(request);
                return new ModelPlan(
                        PlanType.EXECUTION,
                        PlanOutcome.CONTINUE,
                        "Reply to the user",
                        List.of(new ModelPlan.Step(
                                "step-1",
                                "Echo input",
                                false,
                                "echo",
                                Map.of("message", request.agentRequest().objective()))),
                        null);
            };
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5),
                    new AgentExecutionLimits(3, 30, 30, 6));
            AgentRequest agentRequest = AgentRequest.of("session-1", "hello");
            InvocationContext context = InvocationContext.of("main-agent");

            AgentPlan plan = planner.createPlan(agentRequest, context);

            assertThat(plan.objective()).isEqualTo("Reply to the user");
            assertThat(plan.origin()).isEqualTo(PlanOrigin.INITIAL);
            assertThat(plan.id()).isNotBlank();
            PlanningRequest request = capturedRequest.get();
            assertThat(request.agentRequest()).isEqualTo(agentRequest);
            assertThat(request.agentContext()).isEqualTo(context);
            assertThat(request.memoryContext().recentTurns()).hasSize(1);
            assertThat(request.memoryContext().formattedContext())
                    .contains("L0 Recent completed turns", "<memory_context>");
            assertThat(request.maxSteps()).isEqualTo(5);
            assertThat(request.availableTools()).singleElement().satisfies(tool ->
                    assertThat(tool.name()).isEqualTo("echo"));
        }
    }

    @Test
    void allowsCompleteReplanWhenStepBudgetIsFullyConsumed() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));
        PlanStep currentStep = new PlanStep(
                "step-29", "last step", false,
                new ToolCall("echo", Map.of("message", "done")));
        AgentPlan previousPlan = new AgentPlan(
                "plan-29",
                PlanType.EXECUTION,
                PlanOrigin.REPLANNED,
                PlanOutcome.CONTINUE,
                "execute",
                List.of(currentStep),
                "");
        List<StepResult> results = IntStream.range(0, 30)
                .mapToObj(index -> new StepResult(
                        "plan-" + index,
                        "step-" + index,
                        "echo",
                        StepStatus.COMPLETED,
                        "result-" + index,
                        "",
                        ToolFailureType.NONE,
                        1))
                .toList();
        PlanExecutionSnapshot snapshot = new PlanExecutionSnapshot(
                results,
                new DefaultObservationSummarizer().summarize(results),
                currentStep,
                results.getLast(),
                ReplanReason.EXECUTION_COMPLETED);

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> {
                assertThat(request.maxSteps()).isZero();
                return new ModelPlan(
                        PlanType.EXECUTION,
                        PlanOutcome.COMPLETE,
                        "finish",
                        null,
                        "final answer");
            };
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 10),
                    new AgentExecutionLimits(3, 30, 30, 6));

            AgentPlan result = planner.replan(
                    AgentRequest.of("session-1", "finish"),
                    InvocationContext.of("main-agent"),
                    previousPlan,
                    snapshot);

            assertThat(result.outcome()).isEqualTo(PlanOutcome.COMPLETE);
            assertThat(result.finalAnswer()).isEqualTo("final answer");
        }
    }
}
