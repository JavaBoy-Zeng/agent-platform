package com.github.agentos.planner;

import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolExecutor;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolRegistry;
import com.github.agentos.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 顺序执行一份 CONTINUE 计划，并将失败转换为明确的 Runtime 控制结果。 */
public final class PlanExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanExecutor.class);
    private static final int MAX_LOG_VALUE_LENGTH = 1_000;

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final RiskPolicy riskPolicy;
    private final ApprovalService approvalService;
    private final FailureClassifier failureClassifier;

    public PlanExecutor(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            RiskPolicy riskPolicy,
            ApprovalService approvalService,
            FailureClassifier failureClassifier) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService must not be null");
        this.failureClassifier = Objects.requireNonNull(
                failureClassifier, "failureClassifier must not be null");
    }

    /**
     * 在剩余运行预算内执行计划。步骤预算按实际处理的步骤计算，工具预算包含重试。
     */
    public ExecutionResult execute(
            AgentRequest request,
            AgentContext context,
            AgentPlan plan,
            int remainingStepCount,
            int remainingToolCalls) {
        return execute(
                request, context, plan, remainingStepCount, remainingToolCalls,
                AgentEventSink.NOOP);
    }

    /** 在剩余预算内执行计划，并持续发送不包含原始大结果的工具阶段事件。 */
    public ExecutionResult execute(
            AgentRequest request,
            AgentContext context,
            AgentPlan plan,
            int remainingStepCount,
            int remainingToolCalls,
            AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        if (plan.outcome() != PlanOutcome.CONTINUE) {
            throw new IllegalArgumentException("only CONTINUE plan can be executed");
        }
        if (Thread.currentThread().isInterrupted()) {
            return ExecutionResult.cancelled(plan.id(), List.of(), null, null, 0, 0);
        }
        if (remainingStepCount < plan.steps().size()) {
            return ExecutionResult.aborted(
                    plan.id(), List.of(), null, null, 0, 0,
                    "maxStepCount exhausted before plan execution");
        }
        if (remainingToolCalls <= 0) {
            return ExecutionResult.aborted(
                    plan.id(), List.of(), null, null, 0, 0,
                    "maxToolCalls exhausted before plan execution");
        }

        List<StepResult> results = new ArrayList<>();
        int processedSteps = 0;
        int toolCalls = 0;
        PlanStep lastStep = null;
        StepResult lastResult = null;

        for (int index = 0; index < plan.steps().size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                return ExecutionResult.cancelled(
                        plan.id(), results, lastStep, lastResult, processedSteps, toolCalls);
            }
            PlanStep step = plan.steps().get(index);
            lastStep = step;
            processedSteps++;
            long stepStarted = System.nanoTime();
            LOGGER.info(
                    "[agent-step] started sessionId={} planId={} position={}/{} stepId={} tool={} optional={} description={}",
                    request.sessionId(), plan.id(), index + 1, plan.steps().size(), step.id(),
                    step.toolCall().toolName(), step.optional(), logValue(step.description()));
            emit(eventSink, AgentRunEvent.of(
                    AgentRunEvent.Type.TOOL_STARTED,
                    request.sessionId(),
                    step.description(),
                    java.util.Map.of(
                            "planId", plan.id(),
                            "stepId", step.id(),
                            "toolName", step.toolCall().toolName(),
                            "position", index + 1,
                            "stepCount", plan.steps().size())));

            AgentTool tool = toolRegistry.find(step.toolCall().toolName()).orElse(null);
            if (tool == null) {
                String error = "unknown tool: " + step.toolCall().toolName();
                lastResult = StepResult.failed(
                        plan, step, error, ToolFailureType.UNKNOWN, 0);
                results.add(lastResult);
                logFailure(request, plan, step, lastResult, stepStarted);
                return ExecutionResult.aborted(
                        plan.id(), results, step, lastResult, processedSteps, toolCalls, error);
            }

            if (riskPolicy.requiresApproval(context, tool, step.toolCall())
                    && !approvalService.requestApproval(request, context, tool, step.toolCall())) {
                String error = "human approval was not granted";
                lastResult = StepResult.rejected(plan, step, error);
                results.add(lastResult);
                logFailure(request, plan, step, lastResult, stepStarted);
                return ExecutionResult.aborted(
                        plan.id(), results, step, lastResult, processedSteps, toolCalls, error);
            }

            int attempts = 0;
            while (true) {
                if (toolCalls >= remainingToolCalls) {
                    String error = "maxToolCalls exhausted while executing step " + step.id();
                    return ExecutionResult.aborted(
                            plan.id(), results, step, lastResult, processedSteps, toolCalls, error);
                }
                attempts++;
                toolCalls++;
                ToolResult toolResult = toolExecutor.execute(step.toolCall());
                if (Thread.currentThread().isInterrupted()) {
                    return ExecutionResult.cancelled(
                            plan.id(), results, step, lastResult, processedSteps, toolCalls);
                }
                if (toolResult.success()) {
                    lastResult = StepResult.completed(plan, step, toolResult.output(), attempts);
                    results.add(lastResult);
                    LOGGER.info(
                            "[agent-step] finished sessionId={} planId={} stepId={} status=COMPLETED attempts={} result={} durationMs={}",
                            request.sessionId(), plan.id(), step.id(), attempts,
                            logValue(toolResult.output()), elapsedMillis(stepStarted));
                    emit(eventSink, AgentRunEvent.of(
                            AgentRunEvent.Type.TOOL_FINISHED,
                            request.sessionId(),
                            "工具执行完成",
                            java.util.Map.of(
                                    "planId", plan.id(),
                                    "stepId", step.id(),
                                    "toolName", step.toolCall().toolName(),
                                    "status", StepStatus.COMPLETED.name(),
                                    "attempts", attempts)));
                    break;
                }

                FailureDecision decision = failureClassifier.classify(
                        new FailureContext(plan, step, toolResult, attempts - 1));
                LOGGER.warn(
                        "[agent-step] failed sessionId={} planId={} stepId={} failureType={} action={} attempt={} error={}",
                        request.sessionId(), plan.id(), step.id(), toolResult.failureType(),
                        decision.action(), attempts, logValue(toolResult.error()));
                if (decision.action() == FailureAction.RETRY) {
                    if (toolCalls >= remainingToolCalls) {
                        String error = "maxToolCalls exhausted while retrying step " + step.id();
                        lastResult = StepResult.failed(
                                plan, step, error, toolResult.failureType(), attempts);
                        results.add(lastResult);
                        logFailure(request, plan, step, lastResult, stepStarted);
                        return ExecutionResult.aborted(
                                plan.id(), results, step, lastResult,
                                processedSteps, toolCalls, error);
                    }
                    continue;
                }
                lastResult = decision.action() == FailureAction.SKIP
                        ? StepResult.skipped(plan, step, toolResult.error(), toolResult.failureType(), attempts)
                        : StepResult.failed(plan, step, toolResult.error(), toolResult.failureType(), attempts);
                results.add(lastResult);
                logFailure(request, plan, step, lastResult, stepStarted);
                emit(eventSink, AgentRunEvent.of(
                        AgentRunEvent.Type.TOOL_FINISHED,
                        request.sessionId(),
                        toolResult.error(),
                        java.util.Map.of(
                                "planId", plan.id(),
                                "stepId", step.id(),
                                "toolName", step.toolCall().toolName(),
                                "status", lastResult.status().name(),
                                "failureType", lastResult.failureType().name(),
                                "attempts", attempts)));
                if (decision.action() == FailureAction.SKIP) {
                    break;
                }
                if (decision.action() == FailureAction.REPLAN) {
                    return ExecutionResult.replan(
                            plan.id(), results, step, lastResult, decision.replanReason(),
                            processedSteps, toolCalls);
                }
                return ExecutionResult.aborted(
                        plan.id(), results, step, lastResult, processedSteps, toolCalls,
                        toolResult.error());
            }
        }

        return ExecutionResult.completed(
                plan.id(), results, lastStep, lastResult, processedSteps, toolCalls);
    }

    private static void logFailure(
            AgentRequest request, AgentPlan plan, PlanStep step, StepResult result, long started) {
        LOGGER.warn(
                "[agent-step] finished sessionId={} planId={} stepId={} status={} failureType={} attempts={} error={} durationMs={}",
                request.sessionId(), plan.id(), step.id(), result.status(), result.failureType(),
                result.attempts(), logValue(result.error()), elapsedMillis(started));
    }

    private static void emit(AgentEventSink eventSink, AgentRunEvent event) {
        try {
            eventSink.emit(event);
        } catch (RuntimeException exception) {
            LOGGER.debug("agent event sink rejected event type={}", event.type(), exception);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String logValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= MAX_LOG_VALUE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
    }

    /** 一份计划的执行控制状态。 */
    public enum ExecutionStatus {
        COMPLETED,
        REPLAN_REQUIRED,
        CANCELLED,
        ABORTED
    }

    /** 一份计划的有界执行结果。 */
    public record ExecutionResult(
            String planId,
            ExecutionStatus status,
            List<StepResult> stepResults,
            PlanStep currentStep,
            StepResult lastResult,
            ReplanReason replanReason,
            int processedStepCount,
            int toolCallCount,
            String error) {

        public ExecutionResult {
            stepResults = List.copyOf(stepResults);
            error = error == null ? "" : error;
        }

        static ExecutionResult completed(
                String planId, List<StepResult> results, PlanStep step, StepResult last,
                int processed, int calls) {
            return new ExecutionResult(
                    planId, ExecutionStatus.COMPLETED, results, step, last, null,
                    processed, calls, "");
        }

        static ExecutionResult replan(
                String planId, List<StepResult> results, PlanStep step, StepResult last,
                ReplanReason reason, int processed, int calls) {
            return new ExecutionResult(
                    planId, ExecutionStatus.REPLAN_REQUIRED, results, step, last, reason,
                    processed, calls, "");
        }

        static ExecutionResult aborted(
                String planId, List<StepResult> results, PlanStep step, StepResult last,
                int processed, int calls, String error) {
            return new ExecutionResult(
                    planId, ExecutionStatus.ABORTED, results, step, last, null,
                    processed, calls, error);
        }

        static ExecutionResult cancelled(
                String planId, List<StepResult> results, PlanStep step, StepResult last,
                int processed, int calls) {
            return new ExecutionResult(
                    planId, ExecutionStatus.CANCELLED, results, step, last, null,
                    processed, calls, "run cancelled by user");
        }
    }
}
