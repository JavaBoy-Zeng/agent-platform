package com.github.agentos.agent;

import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.AgentDecision;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.DefaultFailureClassifier;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.PlanOrigin;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanStep;
import com.github.agentos.planner.PlanType;
import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolExecutor;
import com.github.agentos.tool.ToolRegistry;
import com.github.agentos.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MainAgentApprovalResumeTest {

    @Test
    void resumesExactPendingStepWithoutPlanningOrApprovalLoop() {
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger decisionCalls = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AgentTool writeTool = new AgentTool() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "write a file"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.success("written " + call.arguments().get("path"));
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(writeTool));
        PlanExecutor executor = new PlanExecutor(
                registry,
                new ToolExecutor(registry),
                new RiskPolicy(AgentTool.RiskLevel.HIGH),
                new ApprovalService(request -> false),
                new DefaultFailureClassifier());
        AgentPlanner planner = new AgentPlanner() {
            @Override public AgentPlan createPlan(AgentRequest request, AgentContext context) {
                planCalls.incrementAndGet();
                return AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE,
                        "write requested file",
                        List.of(new PlanStep(
                                "write-step", "write file", false,
                                new ToolCall("file_write", Map.of("path", "docs/FEATURES.md")))),
                        "");
            }

            @Override public AgentPlan replan(
                    AgentRequest request, AgentContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                return decide(request, context, previousPlan, snapshot).plan();
            }

            @Override public AgentDecision decide(
                    AgentRequest request, AgentContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                decisionCalls.incrementAndGet();
                return AgentDecision.from(AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                        "file written", List.of(), "文件已写入。"));
            }
        };

        try (MemoryService memory = MemoryService.inMemory()) {
            MainAgent agent = new MainAgent(
                    planner, executor, memory, new DefaultAgentFinalizer(),
                    new AgentExecutionLimits(3, 10, 10, 5));
            AgentRuntime runtime = new AgentRuntime(agent);
            AgentState waiting = runtime.run(
                    AgentRequest.of("session-1", "write file"),
                    AgentContext.of("main-agent"));
            var invocation = runtime.latestInvocation("session-1").orElseThrow();

            assertThat(waiting.status()).isEqualTo(AgentState.Status.WAITING);
            assertThat(writes).hasValue(0);
            assertThat(planCalls).hasValue(1);
            assertThat(invocation.steps()).isZero();
            assertThat(invocation.toolCalls()).isZero();
            assertThat(runtime.checkpoint(invocation.invocationId())).get().satisfies(checkpoint -> {
                assertThat(checkpoint.currentPlanId()).isNotBlank();
                assertThat(checkpoint.currentStepId()).isEqualTo("write-step");
            });

            AgentState resumed = runtime.resume(
                    invocation.invocationId(),
                    PendingActionResolution.approved(
                            invocation.pendingAction().pendingActionId()));

            assertThat(resumed.status()).isEqualTo(AgentState.Status.COMPLETED);
            assertThat(resumed.output()).isEqualTo("文件已写入。");
            assertThat(writes).hasValue(1);
            assertThat(planCalls).hasValue(1);
            assertThat(decisionCalls).hasValue(1);
            assertThat(invocation.steps()).isEqualTo(1);
            assertThat(invocation.toolCalls()).isEqualTo(1);
            assertThat(runtime.checkpoint(invocation.invocationId())).isEmpty();
        }
    }
}
