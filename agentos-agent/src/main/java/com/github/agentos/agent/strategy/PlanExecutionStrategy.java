package com.github.agentos.agent.strategy;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;

import java.util.Objects;

/** 将既有 Plan-and-Execute 流程（如 PlanExecuteAgent）提升为可路由执行策略。 */
public final class PlanExecutionStrategy implements AgentLoop {

    private final AgentLoop delegate;

    /** 创建保留既有 Planner、Replan 与 Finalizer 行为的 PLAN 策略。 */
    public PlanExecutionStrategy(AgentLoop delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        return delegate.run(request, context, runningState);
    }

    @Override
    public AgentState run(
            AgentRequest request, InvocationContext context, AgentState runningState,
            AgentEventSink eventSink) {
        return delegate.run(request, context, runningState, eventSink);
    }
}
