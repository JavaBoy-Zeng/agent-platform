package com.github.agentos.tool.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initialize_sendsHandshakeAndNotification() {
        FakeTransport transport = new FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));

        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).get("method").asText()).isEqualTo("initialize");
        assertThat(transport.notifications).hasSize(1);
        assertThat(transport.notifications.get(0).get("method").asText())
                .isEqualTo("notifications/initialized");
    }

    @Test
    void listTools_returnsToolInfoList() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));

        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        transport.addResponse(objectMapper.readTree("""
                {"tools": [
                  {"name": "read_file", "description": "Read a file", "inputSchema": {"type": "object"}},
                  {"name": "list_dir", "description": "List directory", "inputSchema": {"type": "object"}}
                ]}
                """));

        List<McpClient.McpToolInfo> tools = client.listTools();
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).name()).isEqualTo("read_file");
        assertThat(tools.get(0).description()).isEqualTo("Read a file");
        assertThat(tools.get(1).name()).isEqualTo("list_dir");
    }

    @Test
    void listTools_failsBeforeInitialize() {
        McpClient client = new McpClient(objectMapper, new FakeTransport());
        assertThatThrownBy(client::listTools)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void callTool_sendsNameAndArguments() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));

        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        transport.addResponse(objectMapper.readTree("""
                {"content": [{"type": "text", "text": "hello world"}]}
                """));

        JsonNode result = client.callTool("echo", Map.of("message", "hello world"));
        assertThat(result.get("content").get(0).get("text").asText())
                .isEqualTo("hello world");

        JsonNode lastRequest = transport.requests.get(1);
        assertThat(lastRequest.get("method").asText()).isEqualTo("tools/call");
        assertThat(lastRequest.get("params").get("name").asText()).isEqualTo("echo");
        assertThat(lastRequest.get("params").get("arguments").get("message").asText())
                .isEqualTo("hello world");
    }

    @Test
    void request_propagatesMcpError() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.addResponse(objectMapper.createObjectNode().put("protocolVersion", "2024-11-05"));

        McpClient client = new McpClient(objectMapper, transport);
        client.initialize();

        transport.addResponse(objectMapper.readTree("""
                {"error": {"code": -32602, "message": "invalid params"}}
                """));

        assertThatThrownBy(() -> client.callTool("bad", null))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("invalid params");
    }

    /** 内存版 MCP 传输，按 FIFO 返回预置响应。 */
    static class FakeTransport implements McpTransport {
        private final ObjectMapper mapper = new ObjectMapper();
        final java.util.Queue<JsonNode> responses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.List<JsonNode> requests = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<JsonNode> notifications = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        void addResponse(JsonNode response) {
            responses.add(response);
        }

        @Override
        public JsonNode request(JsonNode request) {
            requests.add(request);
            JsonNode response = responses.isEmpty() ? mapper.createObjectNode() : responses.poll();
            if (response.has("error")) {
                throw new McpException("MCP error: " + response.get("error"));
            }
            return response.get("result") != null ? response.get("result") : response;
        }

        @Override
        public void notify(JsonNode notification) {
            notifications.add(notification);
        }

        @Override
        public void close() {
        }
    }
}
