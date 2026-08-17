package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.runtime.ToolBeforeResult;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolInterceptor;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutorTest {

    @Test
    void retriesTransientFailureOnceAndThenCompletes() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool tool = tool("unstable", call -> calls.getAndIncrement() == 0
                ? ToolResult.failure(ToolFailureType.TRANSIENT, "temporarily unavailable")
                : ToolResult.success("recovered"));
        PlanExecutor executor = executor(tool);

        PlanExecutor.ExecutionResult result = executor.execute(
                AgentRequest.of("session-1", "run"),
                AgentContext.of("main-agent"),
                plan("unstable", false),
                30,
                30);

        assertThat(result.status()).isEqualTo(PlanExecutor.ExecutionStatus.COMPLETED);
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.stepResults()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(step.attempts()).isEqualTo(2);
            assertThat(step.output()).isEqualTo("recovered");
        });
    }

    @Test
    void skipsOptionalInvalidArgumentButAbortsSecurityFailure() {
        PlanExecutor optionalExecutor = executor(tool(
                "optional", call -> ToolResult.failure(
                        ToolFailureType.INVALID_ARGUMENT, "unsupported input")));
        PlanExecutor.ExecutionResult skipped = optionalExecutor.execute(
                AgentRequest.of("session-1", "run"),
                AgentContext.of("main-agent"),
                plan("optional", true),
                30,
                30);

        assertThat(skipped.status()).isEqualTo(PlanExecutor.ExecutionStatus.COMPLETED);
        assertThat(skipped.stepResults()).singleElement().extracting(StepResult::status)
                .isEqualTo(StepStatus.SKIPPED);

        PlanExecutor deniedExecutor = executor(tool(
                "denied", call -> ToolResult.failure(
                        ToolFailureType.SECURITY_DENIED, "blocked by policy")));
        PlanExecutor.ExecutionResult denied = deniedExecutor.execute(
                AgentRequest.of("session-2", "run"),
                AgentContext.of("main-agent"),
                plan("denied", true),
                30,
                30);

        assertThat(denied.status()).isEqualTo(PlanExecutor.ExecutionStatus.ABORTED);
        assertThat(denied.error()).isEqualTo("blocked by policy");
        assertThat(denied.toolCallCount()).isEqualTo(1);
    }

    @Test
    void requestsReplanForNonOptionalNotFound() {
        PlanExecutor executor = executor(tool(
                "lookup", call -> ToolResult.failure(
                        ToolFailureType.NOT_FOUND, "missing path")));

        PlanExecutor.ExecutionResult result = executor.execute(
                AgentRequest.of("session-1", "inspect"),
                AgentContext.of("main-agent"),
                plan("lookup", false),
                30,
                30);

        assertThat(result.status()).isEqualTo(PlanExecutor.ExecutionStatus.REPLAN_REQUIRED);
        assertThat(result.replanReason()).isEqualTo(ReplanReason.INVALID_ASSUMPTION);
    }

    @Test
    void waitsWhenDispatcherReturnsPendingAction() {
        AgentTool risky = new AgentTool() {
            @Override public String name() { return "risky"; }
            @Override public String description() { return "write external state"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.success("must not execute");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(risky));
        ToolInterceptor pendingInterceptor = new ToolInterceptor() {
            @Override
            public ToolBeforeResult beforeExecute(
                    ToolCall call,
                    com.github.agentos.tool.runtime.ToolExecutionContext context) {
                PendingAction action = new PendingAction(
                        "approval-1",
                        PendingActionType.HUMAN_APPROVAL,
                        "Approve risky tool",
                        "write external state",
                        Map.of("toolName", call.toolName()));
                return ToolBeforeResult.shortCircuit(ToolResult.pending(action));
            }
        };
        PlanExecutor executor = new PlanExecutor(
                new ToolDispatcher(registry, List.of(pendingInterceptor)),
                new DefaultFailureClassifier());

        PlanExecutor.ExecutionResult result = executor.execute(
                AgentRequest.of("session-1", "write"),
                AgentContext.of("main-agent"), plan("risky", false), 30, 30);

        assertThat(result.status()).isEqualTo(PlanExecutor.ExecutionStatus.WAITING);
        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().type())
                .isEqualTo(com.github.agentos.kernel.PendingActionType.HUMAN_APPROVAL);
    }

    @Test
    void executesAllToolCallsInOneStepAndAccountsForEachCall() {
        PlanExecutor executor = executor(
                tool("first", call -> ToolResult.success("one")),
                tool("second", call -> ToolResult.success("two")));
        AgentPlan plan = AgentPlan.create(
                PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE, "test",
                List.of(new PlanStep(
                        "step-1", "call tools", false,
                        List.of(new ToolCall("first", Map.of()),
                                new ToolCall("second", Map.of())),
                        ToolExecutionMode.SEQUENTIAL)), "");

        PlanExecutor.ExecutionResult result = executor.execute(
                AgentRequest.of("session-1", "run"), AgentContext.of("main-agent"),
                plan, 30, 30);

        assertThat(result.status()).isEqualTo(PlanExecutor.ExecutionStatus.COMPLETED);
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.stepResults()).singleElement()
                .extracting(StepResult::output).isEqualTo("one\ntwo");
    }

    private static PlanExecutor executor(AgentTool tool) {
        return executor(new AgentTool[]{tool});
    }

    private static PlanExecutor executor(AgentTool... tools) {
        ToolRegistry registry = new ToolRegistry(List.of(tools));
        return new PlanExecutor(
                new ToolDispatcher(registry), new DefaultFailureClassifier());
    }

    private static AgentPlan plan(String toolName, boolean optional) {
        return AgentPlan.create(
                PlanType.EXECUTION,
                PlanOrigin.INITIAL,
                PlanOutcome.CONTINUE,
                "test",
                List.of(new PlanStep(
                        "step-1", "call tool", optional, new ToolCall(toolName, Map.of()))),
                "");
    }

    private static AgentTool tool(String name, ToolBehavior behavior) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return behavior.execute(call);
            }
        };
    }

    @FunctionalInterface
    private interface ToolBehavior {
        ToolResult execute(ToolCall call);
    }
}
