package com.github.agentos.agent.loop;

import com.github.agentos.agent.finalize.DefaultAgentFinalizer;
import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.ApprovalToolInterceptor;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunner;
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
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecuteAgentApprovalResumeTest {

    @Test
    void resumesWriteThenCommitAcrossTwoApprovalsWithoutPlanningLoop() {
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger decisionCalls = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AgentTool writeTool = new AgentTool() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "write a file"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.success("written " + call.arguments().get("path"));
            }
        };
        AgentTool commitTool = new AgentTool() {
            @Override public String name() { return "git_commit"; }
            @Override public String description() { return "commit selected files"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                commits.incrementAndGet();
                return ToolResult.success("committed");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(writeTool, commitTool));
        PlanExecutor executor = new PlanExecutor(
                new ToolDispatcher(
                        registry,
                        List.of(new ApprovalToolInterceptor(
                                new RiskPolicy(AgentTool.RiskLevel.HIGH),
                                new ApprovalService(request -> false)))),
                new DefaultFailureClassifier());
        AgentPlanner planner = new AgentPlanner() {
            @Override public AgentPlan createPlan(AgentRequest request, InvocationContext context) {
                planCalls.incrementAndGet();
                return AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE,
                        "write requested file",
                        List.of(
                                new PlanStep(
                                        "write-step", "write file", false,
                                        new ToolCall("file_write", Map.of(
                                                "path", "docs/FEATURES.md"))),
                                new PlanStep(
                                        "commit-step", "commit file", false,
                                        new ToolCall("git_commit", Map.of(
                                                "paths", List.of("docs/FEATURES.md"),
                                                "message", "docs: add features")))),
                        "");
            }

            @Override public AgentPlan replan(
                    AgentRequest request, InvocationContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                return decide(request, context, previousPlan, snapshot).plan();
            }

            @Override public AgentDecision decide(
                    AgentRequest request, InvocationContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                decisionCalls.incrementAndGet();
                return AgentDecision.from(AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                        "file written", List.of(), "文件已写入。"));
            }
        };

        try (MemoryService memory = MemoryService.inMemory()) {
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    planner, executor, memory, new DefaultAgentFinalizer(),
                    new AgentExecutionLimits(3, 10, 10, 5));
            AgentRunner runtime = new AgentRunner(agent);
            AgentState waiting = runtime.run(
                    AgentRequest.of("session-1", "write file"),
                    InvocationContext.of("plan-execute-agent"));
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

            assertThat(resumed.status()).isEqualTo(AgentState.Status.WAITING);
            assertThat(writes).hasValue(1);
            assertThat(commits).hasValue(0);
            assertThat(planCalls).hasValue(1);
            assertThat(decisionCalls).hasValue(0);
            assertThat(invocation.steps()).isEqualTo(1);
            assertThat(invocation.toolCalls()).isEqualTo(1);

            AgentState committed = runtime.resume(
                    invocation.invocationId(),
                    PendingActionResolution.approved(
                            invocation.pendingAction().pendingActionId()));

            assertThat(committed.status()).isEqualTo(AgentState.Status.COMPLETED);
            assertThat(committed.output()).isEqualTo("文件已写入。");
            assertThat(writes).hasValue(1);
            assertThat(commits).hasValue(1);
            assertThat(planCalls).hasValue(1);
            assertThat(decisionCalls).hasValue(1);
            assertThat(invocation.steps()).isEqualTo(2);
            assertThat(invocation.toolCalls()).isEqualTo(2);
            assertThat(runtime.checkpoint(invocation.invocationId())).isEmpty();
        }
    }
}
