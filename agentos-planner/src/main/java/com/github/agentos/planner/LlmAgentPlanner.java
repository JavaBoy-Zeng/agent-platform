package com.github.agentos.planner;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.memory.MemoryContext;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.file.Path;

/** 使用大语言模型生成初始计划和基于执行快照的重规划计划。 */
public final class LlmAgentPlanner implements AgentPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmAgentPlanner.class);

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final PlanValidator planValidator;
    private final AgentExecutionLimits limits;
    private final Path fileAccessRoot;

    /** 创建迭代式大语言模型规划器。 */
    public LlmAgentPlanner(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator,
            AgentExecutionLimits limits) {
        this(modelClient, toolRegistry, memoryService, planValidator, limits, null);
    }

    /** 创建带固定文件访问根目录事实的迭代式大语言模型规划器。 */
    public LlmAgentPlanner(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator,
            AgentExecutionLimits limits,
            Path fileAccessRoot) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.planValidator = Objects.requireNonNull(planValidator, "planValidator must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.fileAccessRoot = fileAccessRoot == null
                ? null
                : fileAccessRoot.toAbsolutePath().normalize();
    }

    /** 创建带模型生命周期拦截器的迭代式规划器。 */
    public LlmAgentPlanner(
            ModelClient modelClient,
            List<ModelInterceptor> modelInterceptors,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator,
            AgentExecutionLimits limits) {
        this(new InterceptingModelClient(modelClient, modelInterceptors), toolRegistry,
                memoryService, planValidator, limits);
    }

    @Override
    public AgentPlan createPlan(AgentRequest request, InvocationContext context) {
        return generate(request, context, null, null, PlanOrigin.INITIAL);
    }

    @Override
    public AgentPlan replan(
            AgentRequest request,
            InvocationContext context,
            AgentPlan previousPlan,
            PlanExecutionSnapshot snapshot) {
        Objects.requireNonNull(previousPlan, "previousPlan must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return decide(request, context, previousPlan, snapshot).plan();
    }

    @Override
    public AgentDecision decide(
            AgentRequest request,
            InvocationContext context,
            AgentPlan previousPlan,
            PlanExecutionSnapshot snapshot) {
        Objects.requireNonNull(previousPlan, "previousPlan must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return AgentDecision.from(
                generate(request, context, previousPlan, snapshot, PlanOrigin.REPLANNED));
    }

    private AgentPlan generate(
            AgentRequest request,
            InvocationContext context,
            AgentPlan previousPlan,
            PlanExecutionSnapshot snapshot,
            PlanOrigin origin) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        java.util.Optional<AgentPlan> boundaryDenial =
                FileRequestBoundaryPolicy.denyExplicitOutsidePath(
                        request, fileAccessRoot, origin);
        if (boundaryDenial.isPresent()) {
            LOGGER.info(
                    "[agent-file-boundary] denied explicit outside path sessionId={} root={}",
                    request.sessionId(), fileAccessRoot);
            return boundaryDenial.get();
        }
        MemoryScope scope = new MemoryScope(
                context.teamId(),
                context.userId(),
                context.agentId(),
                request.sessionId(),
                context.taskId());
        long recallStarted = System.nanoTime();
        MemoryContext memoryContext = memoryService.recall(scope, request.objective());
        LOGGER.info(
                "[agent-memory] recalled sessionId={} teamId={} userId={} agentId={} taskId={} "
                        + "l0Count={} l1Count={} l2Count={} l3Count={} degraded={} durationMs={}",
                request.sessionId(),
                context.teamId(),
                context.userId(),
                context.agentId(),
                context.taskId(),
                memoryContext.recentTurns().size(),
                memoryContext.atomicMemories().size(),
                memoryContext.scenarios().size(),
                memoryContext.profile() == null ? 0 : 1,
                memoryContext.degraded(),
                elapsedMillis(recallStarted));

        int completedStepCount = snapshot == null ? 0 : snapshot.stepResults().size();
        int remainingSteps = limits.maxStepCount() - completedStepCount;
        PlanningRequest planningRequest = new PlanningRequest(
                request,
                context,
                memoryContext,
                previousPlan,
                snapshot,
                toolRegistry.definitions(),
                Math.clamp(remainingSteps, 0, planValidator.maxSteps()));

        ModelPlan modelPlan = Objects.requireNonNull(
                modelClient.generatePlan(planningRequest),
                "modelClient response must not be null");
        ModelPlan groundedPlan = FileGroundingPolicy.enforce(
                modelPlan, planningRequest, toolRegistry);
        if (groundedPlan != modelPlan) {
            LOGGER.info(
                    "[agent-grounding] replaced model plan sessionId={} originalOutcome={} enforcedOutcome={} enforcedSteps={}",
                    request.sessionId(), modelPlan.outcome(), groundedPlan.outcome(),
                    groundedPlan.steps() == null ? 0 : groundedPlan.steps().size());
        }
        AgentPlan plan = toAgentPlan(groundedPlan, origin);
        if (plan.steps().size() > planningRequest.maxSteps()) {
            throw new PlanValidationException(List.of(
                    "step count " + plan.steps().size()
                            + " exceeds remaining runtime limit " + planningRequest.maxSteps()));
        }
        planValidator.validate(plan);
        return plan;
    }

    private AgentPlan toAgentPlan(ModelPlan modelPlan, PlanOrigin origin) {
        List<String> violations = validateModelStructure(modelPlan);
        if (!violations.isEmpty()) {
            throw new PlanValidationException(violations);
        }
        PlanType effectiveType = normalizePlanType(modelPlan);
        List<PlanStep> steps = modelPlan.steps() == null
                ? List.of()
                : modelPlan.steps().stream()
                        .map(step -> new PlanStep(
                                step.id(),
                                step.description(),
                                // Some compatible providers omit this field even when the
                                // response schema requires it. Missing must remain required.
                                Boolean.TRUE.equals(step.optional()),
                                new ToolCall(step.toolName(), step.arguments())))
                        .toList();
        return AgentPlan.create(
                effectiveType,
                origin,
                modelPlan.outcome(),
                modelPlan.objective(),
                steps,
                modelPlan.finalAnswer());
    }

    private PlanType normalizePlanType(ModelPlan modelPlan) {
        if (modelPlan.type() != PlanType.DISCOVERY
                || modelPlan.outcome() != PlanOutcome.CONTINUE
                || modelPlan.steps() == null) {
            return modelPlan.type();
        }
        boolean containsSideEffectingTool = modelPlan.steps().stream()
                .map(ModelPlan.Step::toolName)
                .map(toolRegistry::find)
                .flatMap(java.util.Optional::stream)
                .anyMatch(tool -> tool.riskLevel() != AgentTool.RiskLevel.LOW);
        if (!containsSideEffectingTool) {
            return modelPlan.type();
        }
        LOGGER.warn(
                "[agent-plan] normalized model plan type from DISCOVERY to EXECUTION "
                        + "because it contains a non-LOW risk tool");
        return PlanType.EXECUTION;
    }

    private List<String> validateModelStructure(ModelPlan modelPlan) {
        List<String> violations = new ArrayList<>();
        if (modelPlan.type() == null) violations.add("type must not be null");
        if (modelPlan.outcome() == null) violations.add("outcome must not be null");
        if (modelPlan.objective() == null || modelPlan.objective().isBlank()) {
            violations.add("objective must not be blank");
        }
        if (modelPlan.outcome() == PlanOutcome.COMPLETE) {
            if (modelPlan.type() != PlanType.EXECUTION) {
                violations.add("COMPLETE plan must have type EXECUTION");
            }
            if (modelPlan.steps() != null && !modelPlan.steps().isEmpty()) {
                violations.add("COMPLETE plan must not contain steps");
            }
            if (modelPlan.finalAnswer() == null || modelPlan.finalAnswer().isBlank()) {
                violations.add("COMPLETE plan must contain finalAnswer");
            }
            return violations;
        }
        if (modelPlan.outcome() == PlanOutcome.CONTINUE
                && (modelPlan.steps() == null || modelPlan.steps().isEmpty())) {
            violations.add("CONTINUE plan must contain at least one step");
            return violations;
        }
        if (modelPlan.outcome() == PlanOutcome.CONTINUE
                && modelPlan.finalAnswer() != null
                && !modelPlan.finalAnswer().isBlank()) {
            violations.add("CONTINUE plan must not contain finalAnswer");
        }

        Set<String> stepIds = new HashSet<>();
        for (int index = 0; modelPlan.steps() != null && index < modelPlan.steps().size(); index++) {
            ModelPlan.Step step = modelPlan.steps().get(index);
            String path = "steps[" + index + "]";
            if (step == null) {
                violations.add(path + " must not be null");
                continue;
            }
            if (step.id() == null || step.id().isBlank()) {
                violations.add(path + ".id must not be blank");
            } else if (!stepIds.add(step.id())) {
                violations.add(path + ".id duplicates " + step.id());
            }
            if (step.description() == null || step.description().isBlank()) {
                violations.add(path + ".description must not be blank");
            }
            if (step.toolName() == null || step.toolName().isBlank()) {
                violations.add(path + ".toolName must not be blank");
            }
            if (step.arguments() == null) violations.add(path + ".arguments must not be null");
        }
        return violations;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
