package com.github.agentos.agent.loop;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.finalize.AgentFinalizer;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingActionResolution;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AgentOS 默认的迭代式主 Agent。
 *
 * <p>Runtime 可以先执行 DISCOVERY 计划，再把累计工具结果反馈给规划器；也可以在明确可恢复
 * 的失败后重新规划。只有模型返回 EXECUTION/COMPLETE 后才由内部 Finalizer 结束运行。</p>
 */
public final class MainAgent implements AgentLoop, Agent {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainAgent.class);
    private static final int MAX_LOG_VALUE_LENGTH = 1_000;
    private static final int OUTPUT_DELTA_LENGTH = 160;
    private final AgentPlanner planner;
    private final PlanExecutor planExecutor;
    private final MemoryService memoryService;
    private final AgentFinalizer finalizer;
    private final AgentExecutionLimits limits;
    private final ObservationSummarizer observationSummarizer;
    private final ContinuationStore continuationStore;
    private final ConcurrentMap<String, Continuation> continuations = new ConcurrentHashMap<>();

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
        this(planner, planExecutor, memoryService, finalizer, limits,
                observationSummarizer, ContinuationStore.NOOP);
    }

    /**
     * 创建带续跑状态持久化的主 Agent；重启后审批恢复依赖该存储。
     */
    public MainAgent(
            AgentPlanner planner,
            PlanExecutor planExecutor,
            MemoryService memoryService,
            AgentFinalizer finalizer,
            AgentExecutionLimits limits,
            ObservationSummarizer observationSummarizer,
            ContinuationStore continuationStore) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.observationSummarizer = Objects.requireNonNull(
                observationSummarizer, "observationSummarizer must not be null");
        this.continuationStore = Objects.requireNonNull(
                continuationStore, "continuationStore must not be null");
    }

    @Override
    public AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    /**
     * 返回默认主 Agent 标识。
     */
    @Override
    public String id() {
        return "main-agent";
    }

    /**
     * 返回当前主 Agent 的能力说明。
     */
    @Override
    public String description() {
        return "AgentOS default planning and tool execution agent";
    }

    /**
     * 通过统一 Agent 抽象执行当前完整 Plan-and-Execute 流程。
     */
    @Override
    public AgentExecutionResult run(
            AgentRequest request,
            InvocationContext context) {
        AgentState result = run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
        return AgentExecutionResult.from(
                result, context.invocation() == null ? null : context.invocation().pendingAction());
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        return run(request, context, runningState, eventSink, null);
    }

    @Override
    public AgentState resume(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentCheckpoint checkpoint,
            PendingActionResolution resolution,
            AgentEventSink eventSink) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        Continuation continuation = takeContinuation(checkpoint.invocationId());
        if (continuation == null) {
            return runningState.fail(
                    "approved invocation cannot resume because its execution continuation is missing");
        }
        return run(continuation.request(), context, runningState, eventSink, continuation);
    }

    @Override
    public AgentCheckpoint checkpoint(
            AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
        Continuation continuation = peekContinuation(checkpoint.invocationId());
        if (continuation == null) {
            return checkpoint;
        }
        return new AgentCheckpoint(
                checkpoint.sessionId(), checkpoint.invocationId(), checkpoint.agentId(),
                checkpoint.taskId(), checkpoint.teamId(), checkpoint.userId(),
                checkpoint.objective(), continuation.remainingPlan().id(),
                continuation.remainingPlan().steps().getFirst().id(), 0,
                continuation.cumulativeResults().stream().map(StepResult::stepId).toList(),
                checkpoint.state(), checkpoint.pendingAction(), checkpoint.executionCounters(),
                checkpoint.status(), checkpoint.savedAt());
    }

    @Override
    public void discard(AgentCheckpoint checkpoint) {
        continuations.remove(checkpoint.invocationId());
        deleteContinuationQuietly(checkpoint.invocationId());
    }

    private AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink,
            Continuation continuation) {
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

        List<StepResult> cumulativeResults = continuation == null
                ? new ArrayList<>() : new ArrayList<>(continuation.cumulativeResults());
        List<Observation> observations = observationSummarizer.summarize(cumulativeResults);
        int modelCalls = continuation == null ? 0 : continuation.modelCalls();
        int replanCount = continuation == null ? 0 : continuation.replanCount();
        int processedSteps = continuation == null ? 0 : continuation.processedSteps();
        int toolCalls = continuation == null ? 0 : continuation.toolCalls();
        AgentPlan plan = continuation == null ? null : continuation.remainingPlan();
        try {
            if (continuation == null) {
                requireNotCancelled();
                modelCalls++;
                incrementModelCalls(context);
                plan = planner.createPlan(request, context);
                logPlan(request, plan, modelCalls, replanCount, processedSteps, toolCalls);
                emitPlan(eventSink, request, plan, modelCalls, replanCount);
            } else {
                LOGGER.info(
                        "[agent-run] resumed sessionId={} invocationId={} planId={} stepId={}",
                        request.sessionId(), context.invocationId(), plan.id(),
                        plan.steps().getFirst().id());
            }

            while (true) {
                // 协作取消检查点：每轮迭代前响应中断，避免取消请求被长循环吞掉。
                requireNotCancelled();
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
                    if (context.invocation() != null) {
                        context.invocation().waitFor(action);
                        PlanStep waitingStep = Objects.requireNonNull(
                                execution.currentStep(), "waiting execution must have currentStep");
                        Continuation saved = new Continuation(
                                request,
                                remainingPlan(plan, waitingStep),
                                cumulativeResults,
                                modelCalls,
                                replanCount,
                                processedSteps,
                                toolCalls);
                        continuations.put(context.invocationId(), saved);
                        saveContinuationQuietly(context.invocationId(), saved);
                    }
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

    private static AgentPlan remainingPlan(AgentPlan plan, PlanStep waitingStep) {
        int index = -1;
        for (int candidate = 0; candidate < plan.steps().size(); candidate++) {
            if (plan.steps().get(candidate).id().equals(waitingStep.id())) {
                index = candidate;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalStateException(
                    "waiting step is not part of current plan: " + waitingStep.id());
        }
        return new AgentPlan(
                plan.id(), plan.type(), plan.origin(), plan.outcome(), plan.objective(),
                plan.steps().subList(index, plan.steps().size()), plan.finalAnswer());
    }

    private record Continuation(
            AgentRequest request,
            AgentPlan remainingPlan,
            List<StepResult> cumulativeResults,
            int modelCalls,
            int replanCount,
            int processedSteps,
            int toolCalls) {

        private Continuation {
            request = Objects.requireNonNull(request, "request must not be null");
            remainingPlan = Objects.requireNonNull(
                    remainingPlan, "remainingPlan must not be null");
            cumulativeResults = List.copyOf(cumulativeResults);
        }
    }

    /**
     * 取出续跑状态：优先内存，其次持久化存储（同时删除，语义与内存 remove 对齐）。
     */
    private Continuation takeContinuation(String invocationId) {
        Continuation inMemory = continuations.remove(invocationId);
        if (inMemory != null) {
            deleteContinuationQuietly(invocationId);
            return inMemory;
        }
        return continuationStore.load(invocationId)
                .map(loaded -> {
                    deleteContinuationQuietly(invocationId);
                    return new Continuation(
                            loaded.request(), loaded.remainingPlan(),
                            loaded.cumulativeResults(), loaded.modelCalls(),
                            loaded.replanCount(), loaded.processedSteps(),
                            loaded.toolCalls());
                })
                .orElse(null);
    }

    /**
     * 只读查看续跑状态，不改变内存或存储。
     */
    private Continuation peekContinuation(String invocationId) {
        Continuation inMemory = continuations.get(invocationId);
        if (inMemory != null) {
            return inMemory;
        }
        return continuationStore.load(invocationId)
                .map(loaded -> new Continuation(
                        loaded.request(), loaded.remainingPlan(),
                        loaded.cumulativeResults(), loaded.modelCalls(),
                        loaded.replanCount(), loaded.processedSteps(),
                        loaded.toolCalls()))
                .orElse(null);
    }

    /**
     * 写透持久化；失败只记日志，不中断已进入 WAITING 的运行。
     */
    private void saveContinuationQuietly(String invocationId, Continuation continuation) {
        try {
            continuationStore.save(invocationId, new ContinuationStore.PersistedContinuation(
                    continuation.request(), continuation.remainingPlan(),
                    continuation.cumulativeResults(), continuation.modelCalls(),
                    continuation.replanCount(), continuation.processedSteps(),
                    continuation.toolCalls()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[agent-continuation] persist failed invocationId={} error={}",
                    invocationId, exception.getMessage());
        }
    }

    private void deleteContinuationQuietly(String invocationId) {
        try {
            continuationStore.delete(invocationId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[agent-continuation] delete failed invocationId={} error={}",
                    invocationId, exception.getMessage());
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
            InvocationContext context,
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
        String memoryBusinessKey = context.invocationId().isBlank()
                ? request.sessionId() + ":" + planId
                : context.invocationId();
        try {
            memoryService.capture(CompletedTurn.success(
                    scope, memoryBusinessKey, request.objective(), finalAnswer, observations));
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

    private static void incrementModelCalls(InvocationContext context) {
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
