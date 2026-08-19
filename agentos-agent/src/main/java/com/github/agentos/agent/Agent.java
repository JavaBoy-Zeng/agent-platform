package com.github.agentos.agent;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;

/** 为未来注册和组合预留的统一 Agent 执行协议。 */
public interface Agent {

    /** 返回 Agent 的稳定唯一标识。 */
    String id();

    /** 返回面向使用者和调度策略的能力说明。 */
    String description();

    /** 在给定执行上下文中运行一次 Agent。 */
    AgentExecutionResult run(AgentRequest request, InvocationContext context);
}
