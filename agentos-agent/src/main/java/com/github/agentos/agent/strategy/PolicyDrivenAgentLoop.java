package com.github.agentos.agent.strategy;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.ExecutionMode;
import com.github.agentos.kernel.ExecutionPolicy;
import com.github.agentos.kernel.InvocationContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 根据 ExecutionPolicy 把请求路由到 DIRECT、REACT 或既有 PLAN 流程。 */
public final class PolicyDrivenAgentLoop implements AgentLoop {

    private final ExecutionPolicy policy;
    private final Map<ExecutionMode, AgentLoop> strategies;

    /** 创建要求三种执行模式均已注册的策略路由器。 */
    public PolicyDrivenAgentLoop(
            ExecutionPolicy policy,
            AgentLoop direct,
            AgentLoop react,
            AgentLoop plan) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        EnumMap<ExecutionMode, AgentLoop> values = new EnumMap<>(ExecutionMode.class);
        values.put(ExecutionMode.DIRECT, Objects.requireNonNull(direct, "direct must not be null"));
        values.put(ExecutionMode.REACT, Objects.requireNonNull(react, "react must not be null"));
        values.put(ExecutionMode.PLAN, Objects.requireNonNull(plan, "plan must not be null"));
        this.strategies = Map.copyOf(values);
    }

    @Override
    public AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    @Override
    public AgentState run(
            AgentRequest request, InvocationContext context, AgentState runningState,
            AgentEventSink eventSink) {
        ExecutionMode mode = policy.select(request, context);
        return strategies.get(mode).run(request, context, runningState, eventSink);
    }
}
