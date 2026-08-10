package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 使用大语言模型结构化输出生成可执行计划的任务规划器。
 *
 * <p>规划器负责收集上下文、记忆和工具定义，调用模型客户端，将模型传输对象转换为
 * 领域计划，并在返回前执行严格校验。模型厂商的提示词和响应解析由 {@link ModelClient}
 * 适配器负责。</p>
 */
public final class LlmTaskPlanner implements TaskPlanner {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final PlanValidator planValidator;

    /**
     * 创建基于大语言模型的任务规划器。
     *
     * @param modelClient 结构化计划模型客户端
     * @param toolRegistry 工具注册表
     * @param memoryService 记忆服务
     * @param planValidator 计划校验器
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     */
    public LlmTaskPlanner(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.planValidator = Objects.requireNonNull(planValidator, "planValidator must not be null");
    }

    /**
     * 收集规划数据、调用模型、转换结构化响应并校验最终计划。
     *
     * @param context 当前 Agent 运行上下文
     * @return 已通过执行前校验的计划
     * @throws NullPointerException 当上下文或模型响应为 {@code null} 时抛出
     * @throws PlanValidationException 当模型响应结构不完整或计划校验失败时抛出
     */
    @Override
    public Plan createPlan(AgentContext context) {
        Objects.requireNonNull(context, "context must not be null");
        MemoryScope scope = new MemoryScope(
                context.teamId(),
                context.userId(),
                context.agentId(),
                context.sessionId(),
                context.taskId());
        PlanningRequest request = new PlanningRequest(
                context.input(),
                context,
                memoryService.recall(scope, context.input()),
                toolRegistry.definitions(),
                planValidator.maxSteps());

        ModelPlan modelPlan = Objects.requireNonNull(
                modelClient.generatePlan(request),
                "modelClient response must not be null");
        Plan plan = toPlan(modelPlan);
        planValidator.validate(plan);
        return plan;
    }

    private Plan toPlan(ModelPlan modelPlan) {
        List<String> violations = validateModelStructure(modelPlan);
        if (!violations.isEmpty()) {
            throw new PlanValidationException(violations);
        }

        List<Plan.Step> steps = modelPlan.steps().stream()
                .map(step -> new Plan.Step(
                        step.id(),
                        step.description(),
                        new ToolCall(step.toolName(), step.arguments())))
                .toList();
        return Plan.of(modelPlan.objective(), steps);
    }

    private List<String> validateModelStructure(ModelPlan modelPlan) {
        List<String> violations = new ArrayList<>();
        if (modelPlan.objective() == null || modelPlan.objective().isBlank()) {
            violations.add("objective must not be blank");
        }
        if (modelPlan.steps() == null || modelPlan.steps().isEmpty()) {
            violations.add("plan must contain at least one step");
            return violations;
        }

        Set<String> stepIds = new HashSet<>();
        for (int index = 0; index < modelPlan.steps().size(); index++) {
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
            if (step.arguments() == null) {
                violations.add(path + ".arguments must not be null");
            }
        }
        return violations;
    }
}
