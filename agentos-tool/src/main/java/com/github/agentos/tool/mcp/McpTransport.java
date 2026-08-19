package com.github.agentos.tool.mcp;

import tools.jackson.databind.JsonNode;

/**
 * MCP 传输层抽象：负责发送 JSON-RPC 请求并返回响应。
 *
 * <p>实现包括 {@link StdioMcpTransport}（子进程 stdio）和未来的 HTTP 传输。
 * 传输层只管消息收发，协议握手和工具列表/调用由 {@link McpClient} 处理。</p>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送 JSON-RPC 请求并同步等待响应。
     *
     * @param request JSON-RPC 请求节点（含 jsonrpc/method/params/id）
     * @return 响应节点（含 result 或 error）
     */
    JsonNode request(JsonNode request);

    /**
     * 发送 JSON-RPC 通知（无需响应）。
     *
     * @param notification 通知节点（不含 id）
     */
    void notify(JsonNode notification);

    /** 关闭传输连接，释放底层资源。 */
    @Override
    void close();
}
