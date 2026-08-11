package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.memory.MemoryContext;
import com.github.agentos.tool.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * 发送给模型客户端的一次不可变规划请求。
 *
 * @param agentRequest 本次用户请求
 * @param agentContext 完整 Agent 运行上下文
 * @param memoryContext 本次召回的 L0-L3 分层记忆上下文
 * @param previousPlan 上一份计划；首次规划时为 {@code null}
 * @param executionSnapshot 累计执行快照；首次规划时为 {@code null}
 * @param availableTools 当前可以调用的工具定义
 * @param maxSteps 允许模型生成的最大步骤数
 */
public record PlanningRequest(
        AgentRequest agentRequest,
        AgentContext agentContext,
        MemoryContext memoryContext,
        AgentPlan previousPlan,
        PlanExecutionSnapshot executionSnapshot,
        List<ToolDefinition> availableTools,
        int maxSteps) {

    /**
     * 创建规划请求并复制所有集合，防止模型调用期间数据被修改。
     *
     * @throws NullPointerException 当输入、上下文或任一集合为 {@code null} 时抛出
     * @throws IllegalArgumentException 当最大步骤数小于零时抛出
     */
    public PlanningRequest {
        agentRequest = Objects.requireNonNull(agentRequest, "agentRequest must not be null");
        agentContext = Objects.requireNonNull(agentContext, "agentContext must not be null");
        memoryContext = Objects.requireNonNull(memoryContext, "memoryContext must not be null");
        availableTools = List.copyOf(Objects.requireNonNull(availableTools, "availableTools must not be null"));
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must not be negative");
        }
        if ((previousPlan == null) != (executionSnapshot == null)) {
            throw new IllegalArgumentException(
                    "previousPlan and executionSnapshot must either both be null or both be present");
        }
    }

    /** 返回是否为根据执行快照发起的重规划请求。 */
    public boolean replanning() {
        return previousPlan != null;
    }
}
