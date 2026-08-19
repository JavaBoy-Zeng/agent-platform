package com.github.agentos.tool.mcp;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把单个 MCP 工具适配为 {@link AgentTool}。
 *
 * <p>Runtime 永远只感知 {@code AgentTool} 接口，不需要知道工具来自 Java、MCP、
 * OpenAPI 还是远端 Agent。{@code McpTool} 在 execute 时通过 {@link McpClient}
 * 发起 JSON-RPC {@code tools/call}，将 MCP 返回的 content 数组拼接为文本输出。</p>
 */
public final class McpTool implements AgentTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpTool.class);

    private final McpClient client;
    private final String toolName;
    private final String description;
    private final String inputSchema;
    private final ObjectMapper objectMapper;

    /**
     * 创建 MCP 工具适配器。
     *
     * @param client       MCP 客户端（已初始化）
     * @param toolName     MCP 工具名
     * @param description  工具描述
     * @param inputSchema  JSON Schema 输入规范（原始 JSON 字符串）
     * @param objectMapper JSON 处理器
     */
    public McpTool(
            McpClient client,
            String toolName,
            String description,
            String inputSchema,
            ObjectMapper objectMapper) {
        this.client = java.util.Objects.requireNonNull(client, "client must not be null");
        this.toolName = java.util.Objects.requireNonNull(toolName, "toolName must not be null");
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null ? "{}" : inputSchema;
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return description;
    }

    /**
     * MCP 工具的参数由远端 Server 的 JSON Schema 定义，不走 {@link #parameters()} 的
     * ToolParameter 列表。直接覆盖 {@link #parametersSchema()} 返回原始 Schema，
     * 让规划器和校验器按 JSON Schema 解析。
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> parametersSchema() {
        try {
            return objectMapper.readValue(inputSchema, Map.class);
        } catch (Exception e) {
            LOGGER.warn("[mcp-tool] failed to parse inputSchema for tool={}", toolName, e);
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            JsonNode result = client.callTool(toolName, call.arguments());
            String text = extractText(result);
            boolean isError = result != null && result.has("isError") && result.get("isError").asBoolean();
            if (isError) {
                return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, text);
            }
            return ToolResult.success(text);
        } catch (McpException e) {
            return ToolResult.failure(ToolFailureType.TRANSIENT, "MCP call failed: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR,
                    "unexpected error: " + e.getMessage());
        }
    }

    /**
     * 从 MCP tools/call 响应中提取文本内容。
     *
     * <p>MCP 返回格式：{@code {content: [{type: "text", text: "..."}, ...]}}
     * 将所有 text 类型内容拼接返回。</p>
     */
    private String extractText(JsonNode result) {
        if (result == null) {
            return "";
        }
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            return result.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : content) {
            String type = item.has("type") ? item.get("type").asText() : "";
            if ("text".equals(type) && item.has("text")) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(item.get("text").asText());
            }
        }
        return sb.toString();
    }
}
