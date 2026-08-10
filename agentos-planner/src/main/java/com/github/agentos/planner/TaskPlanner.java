package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;

/**
 * 根据用户请求和运行上下文生成可执行计划的协议。
 */
@FunctionalInterface
public interface TaskPlanner {

    /**
     * 为当前 Agent 运行创建计划。
     *
     * @param context 包含用户输入和扩展属性的运行上下文
     * @return 可交给 {@link PlanExecutor} 执行的计划
     */
    Plan createPlan(AgentContext context);
}
