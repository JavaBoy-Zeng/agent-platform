package com.github.agentos.tool.api;

import com.github.agentos.kernel.InvocationContext;

import java.util.List;

/**
 * 按 Invocation 作用域提供工具集合的统一来源。
 *
 * <p>注册表、内置工具集、MCP/OpenAPI/Skill 适配器等都实现该接口，
 * 使运行时可以按 Agent、会话或任务动态决定可用工具，而不是全局静态注入。</p>
 */
public interface ToolProvider {

    /**
     * 返回指定 Invocation 作用域下可用的工具列表。
     *
     * @param context 本次运行的 Invocation 上下文
     * @return 工具列表，不提供任何工具时返回空列表
     */
    List<AgentTool> getTools(InvocationContext context);
}
