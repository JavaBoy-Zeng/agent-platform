package com.github.agentos.server.config.mcp;

import com.github.agentos.tool.mcp.McpTool;
import com.github.agentos.tool.mcp.McpToolset;
import com.github.agentos.tool.mcp.StdioMcpTransport;
import com.github.agentos.tool.runtime.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具注册器：在 Spring 容器就绪后启动 MCP Server 子进程，
 * 发现工具并注册到 {@link ToolRegistry}。
 *
 * <p>实现 {@link SmartInitializingSingleton} 确保在 ToolRegistry 等所有单例 bean
 * 创建完成后才执行 MCP 初始化（需要 ToolRegistry 已就绪）。</p>
 *
 * <p>MCP 是可选功能，默认关闭。开启时如果 MCP Server 启动失败，仅记录警告
 * 并跳过该服务器，不影响应用启动。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpToolRegistrar implements SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolRegistrar.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;
    private final boolean allowHostProcesses;
    private final List<McpToolset> toolsets = new ArrayList<>();

    public McpToolRegistrar(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            McpProperties properties,
            @Value("${agentos.security.allow-host-processes:false}")
            boolean allowHostProcesses) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.allowHostProcesses = allowHostProcesses;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            LOGGER.info("[mcp] disabled, skipping MCP server initialization");
            return;
        }
        if (!allowHostProcesses) {
            LOGGER.warn("[mcp] stdio servers disabled by agentos.security.allow-host-processes=false");
            return;
        }
        for (McpProperties.Server server : properties.getServers()) {
            if (server.command() == null || server.command().isEmpty()) {
                LOGGER.warn("[mcp] server {} has empty command, skipping", server.name());
                continue;
            }
            try {
                StdioMcpTransport transport = new StdioMcpTransport(objectMapper, server.command());
                McpToolset toolset = new McpToolset(objectMapper, transport);
                toolset.initialize();
                toolsets.add(toolset);
                int registered = 0;
                for (var tool : toolset.getTools(null)) {
                    try {
                        toolRegistry.register(tool);
                        registered++;
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[mcp] tool {} already registered, skipping", tool.name());
                    }
                }
                LOGGER.info("[mcp] server {} ready, registered {}/{} tools",
                        server.name(), registered, toolset.toolCount());
            } catch (Exception e) {
                LOGGER.warn("[mcp] server {} initialization failed, skipping: {}",
                        server.name(), e.getMessage());
            }
        }
    }

    /**
     * 关闭时清理 MCP 子进程。
     * Spring 容器关闭时会自动调用 DisposableBean，这里也显式提供清理方法。
     */
    public void shutdown() {
        for (McpToolset toolset : toolsets) {
            try {
                toolset.close();
            } catch (Exception e) {
                LOGGER.warn("[mcp] toolset close failed: {}", e.getMessage());
            }
        }
        toolsets.clear();
    }
}
