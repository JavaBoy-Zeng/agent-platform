package com.github.agentos.kernel;

/** Agent 顶层执行策略。 */
public enum ExecutionMode {
    /** 单次模型调用直接生成最终答案。 */
    DIRECT,
    /** 模型与少量工具交替执行。 */
    REACT,
    /** 使用完整 Planner 与 PlanExecutor 流程。 */
    PLAN
}
