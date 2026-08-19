package com.github.agentos.tool.mcp;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolStatus;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 构造一个使用 FakeTransport 的 McpClient，已完成 initialize 握手。 */
    private McpClient newInitializedClient() {
        McpClientTest.FakeTransport transport = new McpClientTest.FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));
        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();
        return client;
    }

    @Test
    void nameAndDescription_fromMcpToolInfo() {
        McpTool tool = new McpTool(
                newInitializedClient(), "read_file", "Read a file from disk", "{}", objectMapper);
        assertThat(tool.name()).isEqualTo("read_file");
        assertThat(tool.description()).isEqualTo("Read a file from disk");
    }

    @Test
    void parametersSchema_parsedFromInputSchema() {
        String schema = """
                {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}""";
        McpTool tool = new McpTool(newInitializedClient(), "read_file", "Read file", schema, objectMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = tool.parametersSchema();
        assertThat(parsed.get("type")).isEqualTo("object");
    }

    @Test
    void parametersSchema_invalidJson_returnsFallback() {
        McpTool tool = new McpTool(newInitializedClient(), "read_file", "Read file", "not json", objectMapper);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = tool.parametersSchema();
        assertThat(parsed.get("type")).isEqualTo("object");
    }

    @Test
    void riskLevel_isMedium_parallelSafeTrue() {
        McpTool tool = new McpTool(newInitializedClient(), "x", "d", "{}", objectMapper);
        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.MEDIUM);
        assertThat(tool.parallelSafe()).isTrue();
    }

    @Test
    void execute_returnsTextContent() throws Exception {
        McpClientTest.FakeTransport transport = new McpClientTest.FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));
        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        transport.addResponse(objectMapper.readTree("""
                {"content": [{"type": "text", "text": "file contents here"}]}
                """));
        McpTool tool = new McpTool(client, "read_file", "Read file", "{}", objectMapper);

        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("read_file", Map.of("path", "/tmp/test.txt")));
        assertThat(result.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("file contents here");
    }

    @Test
    void execute_isErrorFlag_returnsFailure() throws Exception {
        McpClientTest.FakeTransport transport = new McpClientTest.FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));
        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        transport.addResponse(objectMapper.readTree("""
                {"content": [{"type": "text", "text": "file not found"}], "isError": true}
                """));
        McpTool tool = new McpTool(client, "read_file", "Read file", "{}", objectMapper);

        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("read_file", Map.of()));
        assertThat(result.status()).isEqualTo(ToolStatus.FAILURE);
        assertThat(result.error()).contains("file not found");
    }
}
