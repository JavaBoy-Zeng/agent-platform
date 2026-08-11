package com.github.agentos.planner;

/** 计划希望完成的工作类型。 */
public enum PlanType {
    /** 用于探索未知环境并获取后续规划所需的信息。 */
    DISCOVERY,
    /**
     * 基于已确认信息执行实际任务。
     *
     * <p>最终回答也属于执行阶段；此时计划使用 {@link PlanOutcome#COMPLETE}，不再包含工具步骤。</p>
     */
    EXECUTION
}
