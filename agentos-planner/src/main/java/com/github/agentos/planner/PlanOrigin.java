package com.github.agentos.planner;

/** 计划在当前 Agent 运行中的生成来源。 */
public enum PlanOrigin {
    /** 首次规划生成。 */
    INITIAL,
    /** 根据已有执行快照重新规划生成。 */
    REPLANNED
}
