package com.github.agentos.agent.config;

import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.runtime.ToolRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent 工厂构建上下文，提供 Agent 构建时所需的共享依赖。
 *
 * <p>封装 ChatClient、工具注册表等运行时组件，让 {@link AgentFactory}
 * 无需直接依赖 Spring 容器即可解析工具引用与模型客户端。</p>
 *
 * @param chatClient  模型客户端
 * @param toolRegistry 工具注册表（用于按名查找工具）
 */
public record AgentBuildContext(
        ChatClient chatClient,
        ToolRegistry toolRegistry) {

    public AgentBuildContext {
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    }

    /** 按工具名查找工具；不存在时返回 empty。 */
    public Optional<AgentTool> findTool(String toolName) {
        return toolRegistry.find(toolName);
    }
}
