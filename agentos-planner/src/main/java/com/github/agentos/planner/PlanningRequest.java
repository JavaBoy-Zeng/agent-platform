package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.memory.MemoryContext;
import com.github.agentos.tool.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * 发送给模型客户端的一次不可变规划请求。
 *
 * @param userInput 本次用户输入
 * @param agentContext 完整 Agent 运行上下文
 * @param recentMemory 当前会话的短期记忆
 * @param longTermMemory 当前会话的长期事实
 * @param availableTools 当前可以调用的工具定义
 * @param maxSteps 允许模型生成的最大步骤数
 */
public record PlanningRequest(
        String userInput,
        AgentContext agentContext,
        MemoryContext memoryContext,
        List<ToolDefinition> availableTools,
        int maxSteps) {

    /**
     * 创建规划请求并复制所有集合，防止模型调用期间数据被修改。
     *
     * @throws NullPointerException 当输入、上下文或任一集合为 {@code null} 时抛出
     * @throws IllegalArgumentException 当最大步骤数小于或等于零时抛出
     */
    public PlanningRequest {
        userInput = Objects.requireNonNull(userInput, "userInput must not be null");
        agentContext = Objects.requireNonNull(agentContext, "agentContext must not be null");
        memoryContext = Objects.requireNonNull(memoryContext, "memoryContext must not be null");
        availableTools = List.copyOf(Objects.requireNonNull(availableTools, "availableTools must not be null"));
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }
}
