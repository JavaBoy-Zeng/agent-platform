package com.github.agentos.planner;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolDefinition;
import com.github.agentos.tool.ToolParameter;
import com.github.agentos.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在执行前校验模型生成计划的安全边界和工具参数。
 *
 * <p>该校验器只验证计划是否可以安全交给执行器，不负责修复或猜测模型意图。</p>
 */
public final class PlanValidator {

    /** 默认允许的最大计划步骤数。 */
    public static final int DEFAULT_MAX_STEPS = 10;

    private final ToolRegistry toolRegistry;
    private final int maxSteps;

    /**
     * 使用默认最大步骤数创建校验器。
     *
     * @param toolRegistry 当前工具注册表
     */
    public PlanValidator(ToolRegistry toolRegistry) {
        this(toolRegistry, DEFAULT_MAX_STEPS);
    }

    /**
     * 使用指定最大步骤数创建校验器。
     *
     * @param toolRegistry 当前工具注册表
     * @param maxSteps 计划允许包含的最大步骤数
     * @throws NullPointerException 当工具注册表为 {@code null} 时抛出
     * @throws IllegalArgumentException 当最大步骤数小于或等于零时抛出
     */
    public PlanValidator(ToolRegistry toolRegistry, int maxSteps) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    /**
     * 获取允许生成的最大步骤数。
     *
     * @return 最大步骤数
     */
    public int maxSteps() {
        return maxSteps;
    }

    /**
     * 校验计划步骤数、工具存在性以及工具参数。
     *
     * @param plan 待执行计划
     * @throws NullPointerException 当计划为 {@code null} 时抛出
     * @throws PlanValidationException 当计划违反任一校验规则时抛出
     */
    public void validate(Plan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        List<String> violations = new ArrayList<>();
        if (plan.steps().size() > maxSteps) {
            violations.add("step count " + plan.steps().size() + " exceeds limit " + maxSteps);
        }

        for (Plan.Step step : plan.steps()) {
            validateStep(step, violations);
        }

        if (!violations.isEmpty()) {
            throw new PlanValidationException(violations);
        }
    }

    private void validateStep(Plan.Step step, List<String> violations) {
        String toolName = step.toolCall().toolName();
        AgentTool tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            violations.add("step " + step.id() + " references unknown tool " + toolName);
            return;
        }

        ToolDefinition definition = ToolDefinition.from(tool);
        Map<String, ToolParameter> parameters = new HashMap<>();
        for (ToolParameter parameter : definition.parameters()) {
            parameters.put(parameter.name(), parameter);
            if (parameter.required() && !step.toolCall().arguments().containsKey(parameter.name())) {
                violations.add("step " + step.id() + " misses required argument " + parameter.name());
            }
        }

        for (Map.Entry<String, Object> argument : step.toolCall().arguments().entrySet()) {
            ToolParameter parameter = parameters.get(argument.getKey());
            if (parameter == null) {
                violations.add("step " + step.id() + " contains unknown argument " + argument.getKey());
            } else if (!parameter.accepts(argument.getValue())) {
                violations.add("step " + step.id() + " argument " + argument.getKey()
                        + " must be " + parameter.type());
            }
        }
    }
}
