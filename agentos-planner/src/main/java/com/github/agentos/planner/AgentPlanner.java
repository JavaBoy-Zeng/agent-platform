package com.github.agentos.planner;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;

/** 根据用户请求、执行上下文和观察结果动态生成计划的协议。 */
public interface AgentPlanner {

    /** 创建本次运行的初始计划。 */
    AgentPlan createPlan(AgentRequest request, InvocationContext context);

    /** 根据上一份计划及累计执行快照重新规划。 */
    AgentPlan replan(
            AgentRequest request,
            InvocationContext context,
            AgentPlan previousPlan,
            PlanExecutionSnapshot snapshot);

    /**
     * 根据 Observation 判断任务是否已经完成；只有返回 REPLAN 时才消耗重规划预算。
     *
     * <p>默认实现兼容已有 Planner：复用 {@link #replan} 的模型结果并提升为明确决策。</p>
     */
    default AgentDecision decide(
            AgentRequest request,
            InvocationContext context,
            AgentPlan previousPlan,
            PlanExecutionSnapshot snapshot) {
        return AgentDecision.from(replan(request, context, previousPlan, snapshot));
    }
}
