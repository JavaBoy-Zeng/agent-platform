package com.github.agentos.planner;

import com.github.agentos.tool.ToolCall;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一份不可变的 Agent 任务执行计划。
 *
 * @param id 计划唯一标识
 * @param objective 计划需要完成的目标
 * @param steps 按执行顺序排列的计划步骤
 */
public record Plan(String id, String objective, List<Step> steps) {

    /**
     * 创建并校验计划。
     *
     * <p>计划至少包含一个步骤，并且同一计划内的步骤标识必须唯一。</p>
     *
     * @throws IllegalArgumentException 当标识、目标、步骤列表不合法或步骤标识重复时抛出
     * @throws NullPointerException 当步骤列表为 {@code null} 时抛出
     */
    public Plan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("plan must contain at least one step");
        }
        Set<String> ids = new HashSet<>();
        for (Step step : steps) {
            if (!ids.add(step.id())) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
    }

    /**
     * 使用随机标识创建计划。
     *
     * @param objective 计划目标
     * @param steps 按执行顺序排列的步骤
     * @return 带有随机 UUID 标识的计划
     */
    public static Plan of(String objective, List<Step> steps) {
        return new Plan(UUID.randomUUID().toString(), objective, steps);
    }

    /**
     * 计划中的一个不可变执行步骤。
     *
     * @param id 步骤唯一标识
     * @param description 步骤用途说明
     * @param toolCall 本步骤需要执行的工具调用
     */
    public record Step(String id, String description, ToolCall toolCall) {

        /**
         * 创建并校验计划步骤。
         *
         * @throws IllegalArgumentException 当步骤标识或说明为空时抛出
         * @throws NullPointerException 当工具调用为 {@code null} 时抛出
         */
        public Step {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("step id must not be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("step description must not be blank");
            }
            toolCall = Objects.requireNonNull(toolCall, "toolCall must not be null");
        }
    }
}
