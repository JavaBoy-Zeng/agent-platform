package com.github.agentos.kernel;

/** Runtime 可统一挂起等待的外部动作类型。 */
public enum PendingActionType {
    HUMAN_APPROVAL,
    HUMAN_INPUT,
    EXTERNAL_CALLBACK,
    ASYNC_OPERATION
}
