package com.github.agentos.tool.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议客户端：负责 JSON-RPC 握手、工具发现和工具调用。
 *
 * <p>生命周期：</p>
 * <pre>
 * McpClient client = new McpClient(objectMapper, transport);
 * client.initialize();                          // 握手
 * List<McpToolInfo> tools = client.listTools(); // 发现工具
 * JsonNode result = client.callTool("name", args); // 调用工具
 * client.close();                               // 关闭
 * </pre>
 */
public final class McpClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpClient.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "agentos";
    private static final String CLIENT_VERSION = "0.0.1";

    private final ObjectMapper objectMapper;
    private final McpTransport transport;
    private volatile boolean initialized = false;

    public McpClient(ObjectMapper objectMapper, McpTransport transport) {
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    /**
     * 执行 MCP 握手：initialize + initialized 通知。
     */
    public void initialize() {
        ObjectNode params = objectMapper.createObjectNode()
                .put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        params.putObject("capabilities");

        ObjectNode request = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "initialize");
        request.set("params", params);

        JsonNode result = transport.request(request);
        LOGGER.info("[mcp] initialized serverInfo={}", result);
        initialized = true;

        ObjectNode initNotification = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized");
        transport.notify(initNotification);
    }

    /**
     * 调用 tools/list，返回 MCP Server 暴露的工具列表。
     */
    public List<McpToolInfo> listTools() {
        ensureInitialized();
        ObjectNode request = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "tools/list");

        JsonNode result = transport.request(request);
        JsonNode toolsNode = result.get("tools");
        List<McpToolInfo> tools = new ArrayList<>();
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                tools.add(new McpToolInfo(
                        toolNode.get("name").asText(),
                        toolNode.has("description") ? toolNode.get("description").asText() : "",
                        toolNode.has("inputSchema") ? toolNode.get("inputSchema").toString() : "{}"));
            }
        }
        LOGGER.info("[mcp] discovered tools count={}", tools.size());
        return tools;
    }

    /**
     * 调用 tools/call，执行指定工具并返回结果内容。
     */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        ensureInitialized();
        ObjectNode params = objectMapper.createObjectNode()
                .put("name", name);
        if (arguments != null) {
            params.set("arguments", objectMapper.valueToTree(arguments));
        }

        ObjectNode request = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "tools/call");
        request.set("params", params);

        return transport.request(request);
    }

    @Override
    public void close() {
        transport.close();
        initialized = false;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("McpClient not initialized; call initialize() first");
        }
    }

    /**
     * MCP 工具元数据：名称、描述、JSON Schema 输入规范。
     */
    public record McpToolInfo(String name, String description, String inputSchema) {}
}
