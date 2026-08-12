package com.github.agentos.agent;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentDecision;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.DecisionOutcome;
import com.github.agentos.planner.DefaultObservationSummarizer;
import com.github.agentos.planner.Observation;
import com.github.agentos.planner.ObservationSummarizer;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanStep;
import com.github.agentos.planner.PlanType;
import com.github.agentos.planner.ReplanReason;
import com.github.agentos.planner.StepResult;
import com.github.agentos.planner.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AgentOS 默认的迭代式主 Agent。
 *
 * <p>Runtime 可以先执行 DISCOVERY 计划，再把累计工具结果反馈给规划器；也可以在明确可恢复
 * 的失败后重新规划。只有模型返回 EXECUTION/COMPLETE 后才由内部 Finalizer 结束运行。</p>
 */
public final class MainAgent implements AgentLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainAgent.class);
    private static final int MAX_LOG_VALUE_LENGTH = 1_000;
    private static final int OUTPUT_DELTA_LENGTH = 160;
    private final AgentPlanner planner;
    private final PlanExecutor planExecutor;
    private final MemoryService memoryService;
    private final AgentFinalizer finalizer;
    private final AgentExecutionLimits limits;
    private final ObservationSummarizer observationSummarizer;

    public MainAgent(
            AgentPlanner planner,
            PlanExecutor planExecutor,
            MemoryService memoryService,
            AgentFinalizer finalizer,
            AgentExecutionLimits limits) {
        this(
                planner, planExecutor, memoryService, finalizer, limits,
                new DefaultObservationSummarizer());
    }

    public MainAgent(
            AgentPlanner planner,
            PlanExecutor planExecutor,
            MemoryService memoryService,
            AgentFinalizer finalizer,
            AgentExecutionLimits limits,
            ObservationSummarizer observationSummarizer) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.observationSummarizer = Objects.requireNonNull(
                observationSummarizer, "observationSummarizer must not be null");
    }

    @Override
    public AgentState run(AgentRequest request, AgentContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            AgentContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(runningState, "runningState must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        long runStarted = System.nanoTime();
        LOGGER.info(
                "[agent-run] started sessionId={} invocationId={} taskId={} agentId={} iteration={} limits={}",
                request.sessionId(), context.invocationId(), context.taskId(), context.agentId(),
                runningState.iteration(), limits);
        emit(eventSink, AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", context.agentId(), "iteration", runningState.iteration())));

        List<StepResult> cumulativeResults = new ArrayList<>();
        List<Observation> observations = List.of();
        int modelCalls = 0;
        int replanCount = 0;
        int processedSteps = 0;
        int toolCalls = 0;
        AgentPlan plan = null;
        try {
            requireNotCancelled();
            modelCalls++;
            incrementModelCalls(context);
            plan = planner.createPlan(request, context);
            logPlan(request, plan, modelCalls, replanCount, processedSteps, toolCalls);
            emitPlan(eventSink, request, plan, modelCalls, replanCount);

            while (true) {
                if (plan.outcome() == PlanOutcome.COMPLETE) {
                    requireNotCancelled();
                    String finalAnswer = finalizer.finish(request, context, plan);
                    emitAnswerDeltas(eventSink, request.sessionId(), finalAnswer);
                    requireNotCancelled();
                    captureMemory(request, context, finalAnswer, cumulativeResults, plan.id());
                    LOGGER.info(
                            "[agent-run] finished sessionId={} planId={} status=COMPLETED modelCalls={} replans={} steps={} toolCalls={} finalResult={} durationMs={}",
                            request.sessionId(), plan.id(), modelCalls, replanCount, processedSteps,
                            toolCalls, logValue(finalAnswer), elapsedMillis(runStarted));
                    emit(eventSink, AgentRunEvent.of(
                            AgentRunEvent.Type.RUN_COMPLETED,
                            request.sessionId(),
                            finalAnswer,
                            Map.of(
                                    "planId", plan.id(),
                                    "modelCalls", modelCalls,
                                    "replans", replanCount,
                                    "steps", processedSteps,
                                    "toolCalls", toolCalls)));
                    return runningState.complete(finalAnswer);
                }

                PlanExecutor.ExecutionResult execution = planExecutor.execute(
                        request,
                        context,
                        plan,
                        limits.maxStepCount() - processedSteps,
                        limits.maxToolCalls() - toolCalls,
                        eventSink);
                cumulativeResults.addAll(execution.stepResults());
                observations = observationSummarizer.summarize(cumulativeResults);
                List<Observation> latestObservations =
                        observationSummarizer.summarize(execution.stepResults());
                logAndEmitObservations(request, latestObservations, eventSink);
                processedSteps += execution.processedStepCount();
                toolCalls += execution.toolCallCount();

                if (execution.status() == PlanExecutor.ExecutionStatus.CANCELLED) {
                    return cancelled(
                            request, plan, runningState, modelCalls, replanCount,
                            processedSteps, toolCalls, runStarted, eventSink);
                }

                if (execution.status() == PlanExecutor.ExecutionStatus.WAITING) {
                    com.github.agentos.kernel.PendingAction action =
                            Objects.requireNonNull(execution.pendingAction(),
                                    "waiting execution must have pendingAction");
                    emit(eventSink, AgentRunEvent.of(
                            AgentRunEvent.Type.DECISION,
                            request.sessionId(),
                            action.description(),
                            Map.of(
                                    "pendingActionId", action.pendingActionId(),
                                    "pendingActionType", action.type().name(),
                                    "title", action.title())));
                    try {
                        context.eventPublisher().publish(
                                com.github.agentos.kernel.DefaultAgentEvent.of(
                                        context,
                                        com.github.agentos.kernel.AgentEventType.HUMAN_ACTION_REQUIRED,
                                        action.description(),
                                        Map.of(
                                                "pendingActionId", action.pendingActionId(),
                                                "type", action.type().name(),
                                                "title", action.title())));
                    } catch (RuntimeException ignored) {
                        // 观察端不得中断挂起流程。
                    }
                    return runningState.waitForAction(action.description());
                }

                if (execution.status() == PlanExecutor.ExecutionStatus.ABORTED) {
                    String error = execution.error().isBlank()
                            ? "plan execution aborted"
                            : execution.error();
                    return failed(
                            request, plan, runningState, error, modelCalls, replanCount,
                            processedSteps, toolCalls, runStarted, eventSink);
                }

                ReplanReason reason = execution.status()
                        == PlanExecutor.ExecutionStatus.REPLAN_REQUIRED
                        ? execution.replanReason()
                        : plan.type() == PlanType.DISCOVERY
                                ? ReplanReason.DISCOVERY_COMPLETED
                                : ReplanReason.EXECUTION_COMPLETED;
                PlanStep currentStep = Objects.requireNonNull(
                        execution.currentStep(), "completed execution must have currentStep");
                StepResult lastResult = Objects.requireNonNull(
                        execution.lastResult(), "completed execution must have lastResult");
                PlanExecutionSnapshot snapshot = new PlanExecutionSnapshot(
                        cumulativeResults, observations, currentStep, lastResult, reason);

                if (modelCalls >= limits.maxModelCalls()) {
                    return failed(
                            request, plan, runningState,
                            "maxModelCalls exhausted: " + limits.maxModelCalls(),
                            modelCalls, replanCount, processedSteps, toolCalls, runStarted,
                            eventSink);
                }
                AgentPlan previousPlan = plan;
                requireNotCancelled();
                modelCalls++;
                incrementModelCalls(context);
                LOGGER.info(
                        "[agent-decision] started sessionId={} previousPlanId={} reason={} modelCall={}/{} observationCount={}",
                        request.sessionId(), previousPlan.id(), reason,
                        modelCalls, limits.maxModelCalls(), observations.size());
                AgentDecision decision = planner.decide(request, context, previousPlan, snapshot);
                plan = decision.plan();
                LOGGER.info(
                        "[agent-decision] finished sessionId={} previousPlanId={} outcome={} nextPlanId={} modelCalls={} replans={}",
                        request.sessionId(), previousPlan.id(), decision.outcome(), plan.id(),
                        modelCalls, replanCount);
                emit(eventSink, AgentRunEvent.of(
                        AgentRunEvent.Type.DECISION,
                        request.sessionId(),
                        decision.outcome() == DecisionOutcome.COMPLETE
                                ? "信息已充分，生成最终回答"
                                : "信息仍不足，生成后续计划",
                        Map.of(
                                "outcome", decision.outcome().name(),
                                "previousPlanId", previousPlan.id(),
                                "nextPlanId", plan.id(),
                                "reason", reason.name(),
                                "observationCount", observations.size())));
                if (decision.outcome() == DecisionOutcome.REPLAN) {
                    if (replanCount >= limits.maxReplanCount()) {
                        return failed(
                                request, previousPlan, runningState,
                                "maxReplanCount exhausted: " + limits.maxReplanCount(),
                                modelCalls, replanCount, processedSteps, toolCalls, runStarted,
                                eventSink);
                    }
                    replanCount++;
                    if (context.invocation() != null) {
                        context.invocation().incrementReplans();
                    }
                    LOGGER.info(
                            "[agent-replan] accepted sessionId={} previousPlanId={} nextPlanId={} reason={} replan={}/{}",
                            request.sessionId(), previousPlan.id(), plan.id(), reason,
                            replanCount, limits.maxReplanCount());
                    emit(eventSink, AgentRunEvent.of(
                            AgentRunEvent.Type.REPLAN,
                            request.sessionId(),
                            plan.objective(),
                            Map.of(
                                    "previousPlanId", previousPlan.id(),
                                    "planId", plan.id(),
                                    "replanCount", replanCount,
                                    "maxReplanCount", limits.maxReplanCount())));
                }
                logPlan(request, plan, modelCalls, replanCount, processedSteps, toolCalls);
                emitPlan(eventSink, request, plan, modelCalls, replanCount);
            }
        } catch (RuntimeException exception) {
            if (isCancellation(exception)) {
                return cancelled(
                        request, plan, runningState, modelCalls, replanCount,
                        processedSteps, toolCalls, runStarted, eventSink);
            }
            String error = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            LOGGER.error(
                    "[agent-run] failed sessionId={} planId={} status=FAILED modelCalls={} replans={} steps={} toolCalls={} error={} durationMs={}",
                    request.sessionId(), plan == null ? "" : plan.id(), modelCalls, replanCount,
                    processedSteps, toolCalls, logValue(error), elapsedMillis(runStarted), exception);
            emit(eventSink, AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    error,
                    Map.of(
                            "planId", plan == null ? "" : plan.id(),
                            "modelCalls", modelCalls,
                            "replans", replanCount,
                            "steps", processedSteps,
                            "toolCalls", toolCalls)));
            return runningState.fail(error);
        }
    }

    private AgentState cancelled(
            AgentRequest request,
            AgentPlan plan,
            AgentState runningState,
            int modelCalls,
            int replans,
            int steps,
            int toolCalls,
            long runStarted,
            AgentEventSink eventSink) {
        String planId = plan == null ? "" : plan.id();
        String message = "run cancelled by user";
        LOGGER.info(
                "[agent-run] finished sessionId={} planId={} status=CANCELLED modelCalls={} replans={} steps={} toolCalls={} durationMs={}",
                request.sessionId(), planId, modelCalls, replans, steps, toolCalls,
                elapsedMillis(runStarted));
        emit(eventSink, AgentRunEvent.of(
                AgentRunEvent.Type.RUN_CANCELLED,
                request.sessionId(),
                message,
                Map.of(
                        "planId", planId,
                        "modelCalls", modelCalls,
                        "replans", replans,
                        "steps", steps,
                        "toolCalls", toolCalls)));
        return runningState.cancel(message);
    }

    private AgentState failed(
            AgentRequest request,
            AgentPlan plan,
            AgentState runningState,
            String error,
            int modelCalls,
            int replans,
            int steps,
            int toolCalls,
            long runStarted,
            AgentEventSink eventSink) {
        LOGGER.warn(
                "[agent-run] finished sessionId={} planId={} status=FAILED modelCalls={} replans={} steps={} toolCalls={} error={} durationMs={}",
                request.sessionId(), plan.id(), modelCalls, replans, steps, toolCalls,
                logValue(error), elapsedMillis(runStarted));
        emit(eventSink, AgentRunEvent.of(
                AgentRunEvent.Type.RUN_FAILED,
                request.sessionId(),
                error,
                Map.of(
                        "planId", plan.id(),
                        "modelCalls", modelCalls,
                        "replans", replans,
                        "steps", steps,
                        "toolCalls", toolCalls)));
        return runningState.fail(error);
    }

    private static void logPlan(
            AgentRequest request,
            AgentPlan plan,
            int modelCalls,
            int replans,
            int processedSteps,
            int toolCalls) {
        LOGGER.info(
                "[agent-plan] created sessionId={} planId={} type={} origin={} outcome={} objective={} stepCount={} modelCalls={} replans={} steps={} toolCalls={}",
                request.sessionId(), plan.id(), plan.type(), plan.origin(), plan.outcome(),
                logValue(plan.objective()), plan.steps().size(), modelCalls, replans,
                processedSteps, toolCalls);
        for (int index = 0; index < plan.steps().size(); index++) {
            PlanStep step = plan.steps().get(index);
            LOGGER.info(
                    "[agent-plan] step sessionId={} planId={} position={}/{} stepId={} tool={} optional={} description={}",
                    request.sessionId(), plan.id(), index + 1, plan.steps().size(), step.id(),
                    step.toolCall().toolName(), step.optional(), logValue(step.description()));
        }
    }

    private void captureMemory(
            AgentRequest request,
            AgentContext context,
            String finalAnswer,
            List<StepResult> results,
            String planId) {
        MemoryScope scope = new MemoryScope(
                context.teamId(), context.userId(), context.agentId(),
                request.sessionId(), context.taskId());
        List<String> observations = observationSummarizer.summarize(results).stream()
                .filter(observation -> observation.status() == StepStatus.COMPLETED)
                .filter(observation -> !observation.summary().isBlank())
                .map(observation -> observation.toolName() + ": " + observation.summary())
                .toList();
        try {
            memoryService.capture(CompletedTurn.success(
                    scope, request.objective(), finalAnswer, observations));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[agent-memory] capture failed sessionId={} planId={} error={}",
                    request.sessionId(), planId, logValue(exception.getMessage()));
        }
    }

    private static void logAndEmitObservations(
            AgentRequest request,
            List<Observation> observations,
            AgentEventSink eventSink) {
        for (Observation observation : observations) {
            LOGGER.info(
                    "[agent-observation] sessionId={} planId={} stepId={} tool={} status={} failureType={} attempts={} summary={}",
                    request.sessionId(), observation.planId(), observation.stepId(),
                    observation.toolName(), observation.status(), observation.failureType(),
                    observation.attempts(), logValue(observation.summary()));
            emit(eventSink, AgentRunEvent.of(
                    AgentRunEvent.Type.OBSERVATION,
                    request.sessionId(),
                    observation.summary(),
                    Map.of(
                            "planId", observation.planId(),
                            "stepId", observation.stepId(),
                            "toolName", observation.toolName(),
                            "status", observation.status().name(),
                            "failureType", observation.failureType().name(),
                            "attempts", observation.attempts())));
        }
    }

    private static void emitPlan(
            AgentEventSink eventSink,
            AgentRequest request,
            AgentPlan plan,
            int modelCalls,
            int replans) {
        emit(eventSink, AgentRunEvent.of(
                AgentRunEvent.Type.PLAN_CREATED,
                request.sessionId(),
                plan.objective(),
                Map.of(
                        "planId", plan.id(),
                        "type", plan.type().name(),
                        "origin", plan.origin().name(),
                        "outcome", plan.outcome().name(),
                        "stepCount", plan.steps().size(),
                        "modelCalls", modelCalls,
                        "replans", replans)));
    }

    private static void emit(AgentEventSink eventSink, AgentRunEvent event) {
        try {
            eventSink.emit(event);
        } catch (RuntimeException exception) {
            LOGGER.debug("agent event sink rejected event type={}", event.type(), exception);
        }
    }

    private static void emitAnswerDeltas(
            AgentEventSink eventSink, String sessionId, String finalAnswer) {
        int sequence = 0;
        for (int offset = 0; offset < finalAnswer.length(); offset += OUTPUT_DELTA_LENGTH) {
            requireNotCancelled();
            String delta = finalAnswer.substring(
                    offset, Math.min(finalAnswer.length(), offset + OUTPUT_DELTA_LENGTH));
            emit(eventSink, AgentRunEvent.of(
                    AgentRunEvent.Type.OUTPUT_DELTA,
                    sessionId,
                    delta,
                    Map.of("sequence", sequence++)));
        }
    }

    private static void requireNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("run cancelled by user");
        }
    }

    private static void incrementModelCalls(AgentContext context) {
        if (context.invocation() != null) {
            context.invocation().incrementModelCalls();
        }
    }

    private static boolean isCancellation(Throwable exception) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
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
}
