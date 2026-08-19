package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;

import java.util.List;

/**
 * 按声明顺序依次执行子 Agent 的串行编排 Agent。
 *
 * <p>子 Agent 之间通过会话状态共享中间产物；任一子 Agent 进入
 * FAILED / CANCELLED / WAITING 终态时立即短路返回该状态，
 * 全部成功后以最后一个非空输出完成。</p>
 */
public final class SequentialAgent extends BaseAgent {

    /**
     * 创建串行编排 Agent。
     *
     * @param id Agent 稳定唯一标识
     * @param description 能力说明
     * @param subAgents 按执行顺序排列的子 Agent
     */
    public SequentialAgent(String id, String description, List<BaseAgent> subAgents) {
        super(id, description, subAgents);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        AgentState state = runningState;
        String lastOutput = "";
        for (BaseAgent child : subAgents()) {
            state = runChild(child, request, context, state, eventSink);
            if (state.status() != AgentState.Status.COMPLETED) {
                return state;
            }
            if (!state.output().isBlank()) {
                lastOutput = state.output();
            }
        }
        return state.complete(lastOutput);
    }
}
