package com.github.agentos.agent.strategy;

import com.github.agentos.agent.finalize.AgentFinalizer;
import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.DefaultObservationSummarizer;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanStep;
import com.github.agentos.planner.ReplanReason;
import com.github.agentos.planner.StepResult;
import com.github.agentos.planner.StepStatus;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolExecutionContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 在统一模型、工具与预算边界上实现的最小 LLM ↔ Tool REACT 循环。 */
public final class ReactExecutionStrategy implements AgentLoop {

    private final AgentPlanner planner;
    private final ToolDispatcher toolDispatcher;
    private final AgentFinalizer finalizer;
    private final AgentExecutionLimits limits;
    private final DefaultObservationSummarizer observationSummarizer =
            new DefaultObservationSummarizer();

    /** 创建有界 REACT 执行策略。 */
    public ReactExecutionStrategy(
            AgentPlanner planner,
            ToolDispatcher toolDispatcher,
            AgentFinalizer finalizer,
            AgentExecutionLimits limits) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.toolDispatcher = Objects.requireNonNull(
                toolDispatcher, "toolDispatcher must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    @Override
    public AgentState run(AgentRequest request, AgentContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    /** 模型返回 ToolCall 时执行并反馈 Observation，返回 final 时结束。 */
    @Override
    public AgentState run(
            AgentRequest request, AgentContext context, AgentState runningState,
            AgentEventSink eventSink) {
        int modelCalls = 1;
        int toolCalls = 0;
        List<StepResult> cumulative = new ArrayList<>();
        AgentPlan plan = planner.createPlan(request, context);
        while (true) {
            if (plan.outcome() == PlanOutcome.COMPLETE) {
                return runningState.complete(finalizer.finish(request, context, plan));
            }
            for (PlanStep step : plan.steps()) {
                AgentPlan currentPlan = plan;
                if (toolCalls + step.toolCalls().size() > limits.maxToolCalls()) {
                    return runningState.fail(
                            "maxToolCalls exhausted: " + limits.maxToolCalls());
                }
                List<ToolResult> results = toolDispatcher.dispatch(
                        step.toolCalls(), step.executionMode(), tool -> new ToolExecutionContext(
                                request, context, currentPlan.id(), step.id(), limits, Map.of(), tool));
                toolCalls += results.size();
                if (context.invocation() != null) {
                    context.invocation().incrementSteps();
                    for (int index = 0; index < results.size(); index++) {
                        context.invocation().incrementToolCalls();
                    }
                }
                ToolResult pending = results.stream()
                        .filter(result -> result.actions().pendingAction() != null)
                        .findFirst().orElse(null);
                if (pending != null) {
                    if (context.invocation() != null) {
                        context.invocation().waitFor(pending.actions().pendingAction());
                    }
                    return runningState.waitForAction(pending.message());
                }
                ToolResult failed = results.stream()
                        .filter(result -> !result.success()).findFirst().orElse(null);
                if (failed != null) {
                    cumulative.add(stepResult(plan, step, failed, StepStatus.FAILED));
                    return runningState.fail(failed.error());
                }
                String output = results.stream().map(ToolResult::output)
                        .collect(Collectors.joining("\n"));
                cumulative.add(new StepResult(
                        plan.id(), step.id(), step.toolCall().toolName(), StepStatus.COMPLETED,
                        output, "", ToolFailureType.NONE, 1));
                ToolResult end = results.stream()
                        .filter(result -> result.actions().endInvocation()).findFirst().orElse(null);
                if (end != null) {
                    return runningState.complete(end.output());
                }
            }
            if (modelCalls >= limits.maxModelCalls()) {
                return runningState.fail(
                        "maxModelCalls exhausted: " + limits.maxModelCalls());
            }
            StepResult last = cumulative.getLast();
            PlanStep lastStep = plan.steps().getLast();
            PlanExecutionSnapshot snapshot = new PlanExecutionSnapshot(
                    cumulative, observationSummarizer.summarize(cumulative), lastStep, last,
                    ReplanReason.EXECUTION_COMPLETED);
            modelCalls++;
            plan = planner.decide(request, context, plan, snapshot).plan();
        }
    }

    private static StepResult stepResult(
            AgentPlan plan, PlanStep step, ToolResult result, StepStatus status) {
        return new StepResult(
                plan.id(), step.id(), step.toolCall().toolName(), status,
                "", result.error(), result.failureType(), 1);
    }
}
