package com.github.agentos.planner;

/** 工具失败后的 Runtime 控制动作。 */
public enum FailureAction {
    RETRY,
    SKIP,
    REPLAN,
    ABORT
}
