package com.github.agentos.kernel;

/** 根据请求复杂度选择 DIRECT、REACT 或 PLAN 的策略。 */
@FunctionalInterface
public interface ExecutionPolicy {

    /** 为一次请求选择且仅选择一个顶层执行模式。 */
    ExecutionMode select(AgentRequest request, AgentExecutionContext context);
}
