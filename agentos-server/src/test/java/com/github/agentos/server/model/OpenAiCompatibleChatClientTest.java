package com.github.agentos.server.model;

import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsMinimalChatRequestAndParsesAnswer() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "role", "assistant",
                                "content", "JVM 是 Java 虚拟机。")))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "test-chat-model");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);
        LlmRequest request = LlmRequest.of("什么是 JVM")
                .withSystemInstruction("你是直答助手")
                .withModel("minimax-h3");
        String answer = client.chat("s1", request);

        assertThat(answer).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").stringValue()).isEqualTo("minimax-h3");
        assertThat(sent.path("messages").path(0).path("role").stringValue()).isEqualTo("system");
        assertThat(sent.path("messages").path(0).path("content").stringValue())
                .isEqualTo("你是直答助手");
        assertThat(sent.path("messages").path(1).path("role").stringValue()).isEqualTo("user");
        assertThat(sent.path("messages").path(1).path("content").stringValue())
                .isEqualTo("什么是 JVM");
        // 直答路径必须保持轻量：无 response_format、无工具定义。
        assertThat(sent.has("response_format")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void fallsBackToMainModelWhenChatModelUnset() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", "ok")))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);
        client.chat("s1", LlmRequest.of("hi"));

        assertThat(objectMapper.readTree(requestBody.get())
                .path("model").stringValue()).isEqualTo("test-model");
    }

    @Test
    void stripsNamespacedReasoningAndRequestsSeparatedReasoning() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String rawContent = """
                <think>内部推理</think>
                这仍然是草稿
                </mm:think>
                ```markdown
                # 最终文档

                正文
                ```
                """;
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "content", rawContent)))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        properties.setReasoningSplit(true);
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);

        String answer = client.chat("s1", LlmRequest.of("生成文档"));

        assertThat(answer)
                .startsWith("```markdown\n# 最终文档")
                .doesNotContain("内部推理", "草稿", "mm:think");
        assertThat(objectMapper.readTree(requestBody.get())
                .path("reasoning_split").booleanValue()).isTrue();
    }

    @Test
    void rejectsNonStreamingResponseContainingOnlyReasoning() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String response = objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of(
                        "message", java.util.Map.of(
                                "content", "<mm:think>只有推理</mm:think>")))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, response));
        server.start();

        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties("test-model", ""));

        assertThatThrownBy(() -> client.chat("s1", LlmRequest.of("hi")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("reasoning but no final answer");
    }

    @Test
    void rejectsRequestWithoutMessages() {
        // 参数校验发生在 HTTP 请求之前，无需启动 mock server。
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:1/v1/chat/completions"));
        properties.setModel("test-model");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        assertThatThrownBy(() -> client.chat("s1", new LlmRequest(null, java.util.List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void surfacesHttpErrorAsModelClientException() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                respond(exchange, 500, "{\"error\":{\"message\":\"boom\"}}"));
        server.start();

        ModelClientProperties properties = properties("test-model", "");
        ChatClient client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), objectMapper, properties);

        assertThatThrownBy(() -> client.chat("s1", LlmRequest.of("hi")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("boom");
    }

    private ModelClientProperties properties(String model, String chatModel) {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1/chat/completions"));
        properties.setApiKey("test-key");
        properties.setModel(model);
        properties.setChatModel(chatModel);
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
