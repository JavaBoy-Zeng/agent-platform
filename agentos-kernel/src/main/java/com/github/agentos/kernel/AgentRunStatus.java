package com.github.agentos.kernel;

/** 一次 Agent Invocation 的生命周期状态。 */
public enum AgentRunStatus {
    /** 已创建但尚未进入执行循环。 */
    CREATED,
    /** 正在执行。 */
    RUNNING,
    /** 等待外部动作解决后恢复。 */
    WAITING,
    /** 已成功完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED,
    /** 已被取消。 */
    CANCELLED
}
