package com.github.agentos.tool.mcp;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolProvider;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MCP 工具集：管理一个 MCP Server 连接，发现并暴露其全部工具为 {@link AgentTool}。
 *
 * <p>实现 {@link ToolProvider}，使 Runtime 可以像使用内置工具一样使用 MCP 工具。
 * 生命周期：</p>
 * <pre>
 * McpToolset toolset = new McpToolset(objectMapper, transport);
 * toolset.initialize();                              // 握手 + 工具发现
 * List&lt;AgentTool&gt; tools = toolset.getTools(ctx);  // 获取 MCP 工具
 * toolset.close();                                    // 关闭连接
 * </pre>
 *
 * <p>在 Spring 中，{@code McpToolset} 可以作为 {@code AgentTool} 的来源：
 * 其 {@link #getTools(InvocationContext)} 返回的每个 {@code McpTool}
 * 都会被注册到 {@link com.github.agentos.tool.runtime.ToolRegistry}。</p>
 */
public final class McpToolset implements ToolProvider, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolset.class);

    private final ObjectMapper objectMapper;
    private final McpTransport transport;
    private final McpClient client;
    private volatile List<AgentTool> tools = List.of();

    /**
     * 创建 MCP 工具集。
     *
     * @param objectMapper JSON 处理器
     * @param transport    MCP 传输层（未连接）
     */
    public McpToolset(ObjectMapper objectMapper, McpTransport transport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.client = new McpClient(objectMapper, transport);
    }

    /**
     * 执行 MCP 握手并发现工具。
     */
    public void initialize() {
        client.initialize();
        List<McpClient.McpToolInfo> toolInfos = client.listTools();
        List<AgentTool> discovered = new ArrayList<>(toolInfos.size());
        for (McpClient.McpToolInfo info : toolInfos) {
            discovered.add(new McpTool(client, info.name(), info.description(),
                    info.inputSchema(), objectMapper));
        }
        this.tools = List.copyOf(discovered);
        LOGGER.info("[mcp-toolset] initialized tools={}", tools.size());
    }

    @Override
    public List<AgentTool> getTools(InvocationContext context) {
        return tools;
    }

    /** 返回已发现的工具数量。 */
    public int toolCount() {
        return tools.size();
    }

    /** 返回 MCP 客户端（供高级用途，如手动调用）。 */
    public McpClient client() {
        return client;
    }

    @Override
    public void close() {
        client.close();
        LOGGER.info("[mcp-toolset] closed");
    }
}
