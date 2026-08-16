package com.github.agentos.memory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleMemoryAdaptersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsChatCompletionJsonToAllMemoryModelOperations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        List<String> authorization = new ArrayList<>();
        HttpServer server = server(exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String content = switch (calls.getAndIncrement()) {
                case 0 -> """
                        {"memories":[{"type":"PREFERENCE","content":"用户偏好简洁回答",\
                        "confidence":0.93,"priority":8}]}
                        """;
                case 1 -> "{\"content\":\"# 场景\\n- 保持简洁\"}";
                default -> "{\"content\":\"# 画像\\n- 偏好简洁回答\"}";
            };
            respond(exchange, 200, chatResponse(content));
        });
        try {
            OpenAiCompatibleMemoryModel model = new OpenAiCompatibleMemoryModel(
                    HttpClient.newHttpClient(), objectMapper, endpoint(server, "/v1/chat/completions"),
                    "secret", "memory-model", Duration.ofSeconds(2));
            MemoryScope scope = scope();
            CompletedTurn turn = new CompletedTurn(
                    "turn", scope, "请简洁回答", "好的", List.of(), Instant.now());

            assertThat(model.extractAtomic(turn)).singleElement().satisfies(candidate -> {
                assertThat(candidate.type()).isEqualTo(MemoryType.PREFERENCE);
                assertThat(candidate.content()).isEqualTo("用户偏好简洁回答");
                assertThat(candidate.confidence()).isEqualTo(0.93);
                assertThat(candidate.priority()).isEqualTo(8);
            });
            AtomicMemory atomic = AtomicMemory.create(
                    scope, MemoryType.PREFERENCE, "用户偏好简洁回答", 0.93, 8, "turn");
            assertThat(model.synthesizeScenario(scope, List.of(atomic))).contains("保持简洁");
            ScenarioMemory scenario = ScenarioMemory.create(scope, "task:task", "保持简洁");
            assertThat(model.synthesizeProfile(scope, List.of(atomic), List.of(scenario)))
                    .contains("偏好简洁回答");

            assertThat(requests).hasSize(3);
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.path("model").asString()).isEqualTo("memory-model");
                assertThat(request.path("response_format").path("type").asString()).isEqualTo("json_object");
            });
            assertThat(authorization).containsOnly("Bearer secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsEmbeddingResponseAndValidatesFiniteValues() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server(exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"data\":[{\"embedding\":[0.25,-0.5,1.0]}]}");
        });
        try {
            OpenAiCompatibleMemoryEmbedding embedding = new OpenAiCompatibleMemoryEmbedding(
                    HttpClient.newHttpClient(), objectMapper, endpoint(server, "/v1/embeddings"),
                    "", "embedding-model", Duration.ofSeconds(2));

            assertThat(embedding.embed("Java concurrency")).containsExactly(0.25, -0.5, 1.0);
            assertThat(requests).singleElement().satisfies(request -> {
                assertThat(request.path("model").asString()).isEqualTo("embedding-model");
                assertThat(request.path("input").asString()).isEqualTo("Java concurrency");
                assertThat(request.path("encoding_format").asString()).isEqualTo("float");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesProviderErrorsWithoutLeakingBeyondBoundedResponseBody() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 429, "rate limited"));
        try {
            OpenAiCompatibleMemoryEmbedding embedding = new OpenAiCompatibleMemoryEmbedding(
                    HttpClient.newHttpClient(), objectMapper, endpoint(server, "/v1/embeddings"),
                    "secret", "embedding-model", Duration.ofSeconds(2));

            assertThatThrownBy(() -> embedding.embed("text"))
                    .isInstanceOf(MemoryAdapterException.class)
                    .hasMessageContaining("HTTP 429")
                    .hasMessageContaining("rate limited");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                respond(exchange, 500, exception.toString());
            }
        });
        server.start();
        return server;
    }

    private String chatResponse(String content) throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content)))));
    }

    private static URI endpoint(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static MemoryScope scope() {
        return new MemoryScope("team", "user", "agent", "session", "task");
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
