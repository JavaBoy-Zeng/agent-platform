package com.github.agentos.server.config.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器配置属性。
 *
 * <p>在 application.yml 中配置：</p>
 * <pre>
 * agentos:
 *   mcp:
 *     enabled: true
 *     servers:
 *       - name: filesystem
 *         command:
 *           - npx
 *           - "@modelcontextprotocol/server-filesystem"
 *           - /tmp
 * </pre>
 */
@ConfigurationProperties("agentos.mcp")
public class McpProperties {

    private boolean enabled = false;
    private List<Server> servers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers == null ? new ArrayList<>() : servers;
    }

    /**
     * 单个 MCP 服务器配置。
     *
     * @param name     服务器名称（用于日志和工具名前缀）
     * @param command  启动命令（如 ["npx", "@modelcontextprotocol/server-filesystem", "/tmp"]）
     */
    public record Server(String name, List<String> command) {}
}
