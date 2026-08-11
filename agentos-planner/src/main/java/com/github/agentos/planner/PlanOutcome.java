package com.github.agentos.planner;

/** 计划返回后 Runtime 应继续执行还是结束运行。 */
public enum PlanOutcome {
    /** 执行计划步骤并根据结果继续规划。 */
    CONTINUE,
    /** 信息已经足够，由 Runtime Finalizer 输出最终回答。 */
    COMPLETE
}
