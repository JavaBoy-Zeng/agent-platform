package com.github.agentos.planner;

import java.util.Objects;

/**
 * Planner 对累计 Observation 作出的明确控制决策。
 *
 * @param outcome 完成或继续规划
 * @param plan COMPLETE 最终计划或 REPLAN 后续计划
 */
public record AgentDecision(DecisionOutcome outcome, AgentPlan plan) {

    /** 创建并校验决策与计划结果的一致性。 */
    public AgentDecision {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        plan = Objects.requireNonNull(plan, "plan must not be null");
        if (outcome == DecisionOutcome.COMPLETE && plan.outcome() != PlanOutcome.COMPLETE) {
            throw new IllegalArgumentException("COMPLETE decision requires a COMPLETE plan");
        }
        if (outcome == DecisionOutcome.REPLAN && plan.outcome() != PlanOutcome.CONTINUE) {
            throw new IllegalArgumentException("REPLAN decision requires a CONTINUE plan");
        }
    }

    /** 根据模型计划结果创建 Runtime 决策。 */
    public static AgentDecision from(AgentPlan plan) {
        return new AgentDecision(
                plan.outcome() == PlanOutcome.COMPLETE
                        ? DecisionOutcome.COMPLETE
                        : DecisionOutcome.REPLAN,
                plan);
    }
}
