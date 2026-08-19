package com.github.agentos.agent.finalize;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.AgentPlan;

/** Runtime 内部的最终回答收口动作，不作为可被模型调用的外部工具。 */
public interface AgentFinalizer {

    /** 校验并返回 COMPLETE 计划携带的最终回答。 */
    String finish(AgentRequest request, InvocationContext context, AgentPlan completedPlan);
}
