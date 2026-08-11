package com.github.agentos.planner;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime 可以执行或完成的一份不可变 Agent 计划。
 *
 * @param id Runtime 生成的计划标识
 * @param type 计划工作类型
 * @param origin 计划生成来源
 * @param outcome 继续执行或直接完成
 * @param objective 计划目标
 * @param steps CONTINUE 计划的工具步骤
 * @param finalAnswer COMPLETE 计划的最终回答
 */
public record AgentPlan(
        String id,
        PlanType type,
        PlanOrigin origin,
        PlanOutcome outcome,
        String objective,
        List<PlanStep> steps,
        String finalAnswer) {

    /** 创建并校验领域计划。 */
    public AgentPlan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        type = Objects.requireNonNull(type, "type must not be null");
        origin = Objects.requireNonNull(origin, "origin must not be null");
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        finalAnswer = finalAnswer == null ? "" : finalAnswer;

        if (outcome == PlanOutcome.CONTINUE) {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("CONTINUE plan must contain at least one step");
            }
            if (!finalAnswer.isBlank()) {
                throw new IllegalArgumentException("CONTINUE plan must not contain finalAnswer");
            }
        } else {
            if (type != PlanType.EXECUTION) {
                throw new IllegalArgumentException("COMPLETE plan must have type EXECUTION");
            }
            if (!steps.isEmpty()) {
                throw new IllegalArgumentException("COMPLETE plan must not contain steps");
            }
            if (finalAnswer.isBlank()) {
                throw new IllegalArgumentException("COMPLETE plan must contain finalAnswer");
            }
        }

        Set<String> stepIds = new HashSet<>();
        for (PlanStep step : steps) {
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException("duplicate step id: " + step.id());
            }
        }
    }

    /** 使用 Runtime 生成的 UUID 创建计划。 */
    public static AgentPlan create(
            PlanType type,
            PlanOrigin origin,
            PlanOutcome outcome,
            String objective,
            List<PlanStep> steps,
            String finalAnswer) {
        return new AgentPlan(
                UUID.randomUUID().toString(), type, origin, outcome, objective, steps, finalAnswer);
    }
}
