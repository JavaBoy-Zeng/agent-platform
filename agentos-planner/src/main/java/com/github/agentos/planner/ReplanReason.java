package com.github.agentos.planner;

/** Runtime 请求重新规划的原因。 */
public enum ReplanReason {
    /** 探索阶段获得了新的环境信息。 */
    DISCOVERY_COMPLETED,
    /** 工具执行失败，但任务仍然可以恢复。 */
    RECOVERABLE_FAILURE,
    /** 原计划中的假设已经失效。 */
    INVALID_ASSUMPTION,
    /** 执行结果导致后续步骤需要调整。 */
    PLAN_ADJUSTMENT,
    /** 实际执行完成，需要综合结果并形成最终回答。 */
    EXECUTION_COMPLETED
}
