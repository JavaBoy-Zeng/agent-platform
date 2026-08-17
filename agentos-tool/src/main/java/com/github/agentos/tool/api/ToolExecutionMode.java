package com.github.agentos.tool.api;

/** 一个计划步骤内多个工具调用的调度方式。 */
public enum ToolExecutionMode {
    /** 按 ToolCall 声明顺序逐个执行。 */
    SEQUENTIAL,
    /** 仅在所有工具显式声明并行安全时并发执行。 */
    PARALLEL
}
