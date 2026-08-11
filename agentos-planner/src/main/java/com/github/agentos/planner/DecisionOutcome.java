package com.github.agentos.planner;

/** Planner 检查 Observation 后给 Runtime 的控制决策。 */
public enum DecisionOutcome {
    /** 已有信息足够，由 Runtime 进入最终回答阶段。 */
    COMPLETE,
    /** 信息或执行仍不足，需要执行下一份计划。 */
    REPLAN
}
