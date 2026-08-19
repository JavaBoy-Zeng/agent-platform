package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;

import java.util.List;

/**
 * 循环执行子 Agent 直到显式终止或达到迭代上限的编排 Agent。
 *
 * <p>每轮按声明顺序执行全部子 Agent。终止条件：</p>
 * <ul>
 *   <li>任一子 Agent 进入 FAILED / CANCELLED / WAITING 终态：短路返回该状态</li>
 *   <li>任一子 Agent 输出以 {@link #ESCALATE_PREFIX} 开头：显式终止，
 *       去掉前缀后的剩余文本作为最终输出</li>
 *   <li>达到 {@code maxIterations}：以最后一个非空输出完成</li>
 * </ul>
 */
public final class LoopAgent extends BaseAgent {

    /** 子 Agent 输出中显式终止循环的约定前缀。 */
    public static final String ESCALATE_PREFIX = "escalate:";

    private final int maxIterations;

    /**
     * 创建循环编排 Agent。
     *
     * @param id Agent 稳定唯一标识
     * @param description 能力说明
     * @param subAgents 每轮按序执行的子 Agent
     * @param maxIterations 最大迭代轮数
     * @throws IllegalArgumentException 当迭代上限不是正数时抛出
     */
    public LoopAgent(
            String id, String description, List<BaseAgent> subAgents, int maxIterations) {
        super(id, description, subAgents);
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        this.maxIterations = maxIterations;
    }

    /** 返回最大迭代轮数。 */
    public int maxIterations() {
        return maxIterations;
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        AgentState state = runningState;
        String lastOutput = "";
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            for (BaseAgent child : subAgents()) {
                state = runChild(child, request, context, state, eventSink);
                if (state.status() != AgentState.Status.COMPLETED) {
                    return state;
                }
                if (state.output().startsWith(ESCALATE_PREFIX)) {
                    return state.complete(
                            state.output().substring(ESCALATE_PREFIX.length()).strip());
                }
                if (!state.output().isBlank()) {
                    lastOutput = state.output();
                }
            }
        }
        return state.complete(lastOutput);
    }
}
