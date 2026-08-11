package com.github.agentos.planner;

/** 计划步骤的终态。 */
public enum StepStatus {
    /** 工具调用成功。 */
    COMPLETED,
    /** 工具调用失败。 */
    FAILED,
    /** 可选步骤被失败分类器跳过。 */
    SKIPPED,
    /** 步骤未通过人工审批。 */
    REJECTED
}
