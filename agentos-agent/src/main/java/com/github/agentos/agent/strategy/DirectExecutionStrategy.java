package com.github.agentos.agent.strategy;

import com.github.agentos.agent.finalize.AgentFinalizer;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.PlanOutcome;

import java.util.Objects;

/** 只允许模型直接返回最终答案、禁止隐式执行工具的 DIRECT 策略。 */
public final class DirectExecutionStrategy implements AgentLoop {

    private final AgentPlanner planner;
    private final AgentFinalizer finalizer;

    /** 创建单模型调用策略。 */
    public DirectExecutionStrategy(AgentPlanner planner, AgentFinalizer finalizer) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
    }

    @Override
    public AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        AgentPlan response = planner.createPlan(request, context);
        if (response.outcome() != PlanOutcome.COMPLETE) {
            return runningState.fail("DIRECT execution requires a final model response");
        }
        return runningState.complete(finalizer.finish(request, context, response));
    }
}
