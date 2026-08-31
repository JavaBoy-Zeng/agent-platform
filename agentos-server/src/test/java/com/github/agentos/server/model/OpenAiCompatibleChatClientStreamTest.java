package com.github.agentos.server.model;

import com.github.agentos.kernel.ModelUsage;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.ModelUsageListener;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 直答客户端流式路径测试（本地 SSE mock）。 */
class OpenAiCompatibleChatClientStreamTest {

    private HttpServer server;
    private OpenAiCompatibleChatClient client;
    private ModelClientProperties properties;
    private String sseBody;
    private String jsonBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            boolean streamMode = jsonBody == null;
            byte[] body = (streamMode ? sseBody : jsonBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    streamMode ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        properties = new ModelClientProperties();
        properties.setEndpoint(
                java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/chat"));
        properties.setApiKey("test-key");
        properties.setModel("test-model");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void serve(String chunksJson) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunksJson.split("\n")) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        sseBody = body.toString();
    }

    @Test
    void streamsDeltasAndFiltersThinkBlock() {
        serve("""
                {"choices":[{"delta":{"content":"<think>推理过程"}}]}
                {"choices":[{"delta":{"content":"应当被过滤</think>"}}]}
                {"choices":[{"delta":{"content":"JVM 是"}}]}
                {"choices":[{"delta":{"content":"Java 虚拟机。"}}]}
                {"usage":{"prompt_tokens":10,"completion_tokens":6}}
                """);
        List<String> deltas = new ArrayList<>();
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        ChatClient.ChatResponse response = client.chatStream(
                "s1", LlmRequest.of("什么是JVM"), deltas::add);

        assertThat(response.answer()).isEqualTo("JVM 是Java 虚拟机。");
        assertThat(deltas).containsExactly("JVM 是", "Java 虚拟机。");
        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().totalTokens()).isEqualTo(16);
    }

    @Test
    void capturesReasoningContentFromStreamedDelta() {
        serve("""
                {"choices":[{"delta":{"reasoning_content":"用户问日期 "}}]}
                {"choices":[{"delta":{"reasoning_content":"→ 查日历 → 周三"}}]}
                {"choices":[{"delta":{"content":"今天"}}]}
                {"choices":[{"delta":{"content":"是周三"}}]}
                {"usage":{"prompt_tokens":5,"completion_tokens":4}}
                """);
        List<String> deltas = new ArrayList<>();
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        ChatClient.ChatResponse response = client.chatStream(
                "s1", LlmRequest.of("今天周几"), deltas::add);

        assertThat(response.answer()).isEqualTo("今天是周三");
        assertThat(response.reasoningContent()).isEqualTo("用户问日期 → 查日历 → 周三");
        // 推理内容不应进入 onDelta 回调。
        assertThat(deltas).containsExactly("今天", "是周三");
    }

    @Test
    void deliversFirstDeltaBeforeTheProviderResponseCompletes() throws Exception {
        CountDownLatch firstChunkWritten = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        server.createContext("/chat-delayed", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                firstChunkWritten.countDown();
                if (!allowCompletion.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release delayed SSE response");
                }
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        properties.setEndpoint(java.net.URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/chat-delayed"));
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);
        CountDownLatch firstDeltaReceived = new CountDownLatch(1);
        List<String> deltas = new ArrayList<>();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var responseFuture = executor.submit(() -> client.chatStream(
                    "s1", LlmRequest.of("hi"), delta -> {
                        deltas.add(delta);
                        firstDeltaReceived.countDown();
                    }));

            assertThat(firstChunkWritten.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstDeltaReceived.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(deltas).containsExactly("first");
            assertThat(responseFuture.isDone()).isFalse();

            allowCompletion.countDown();
            assertThat(responseFuture.get(2, TimeUnit.SECONDS).answer())
                    .isEqualTo("firstsecond");
        } finally {
            allowCompletion.countDown();
        }
        assertThat(deltas).containsExactly("first", "second");
    }

    @Test
    void filtersNamespacedReasoningWhenTagsAreSplitAcrossDeltas() {
        serve("""
                {"choices":[{"delta":{"content":"<mm:thi"}}]}
                {"choices":[{"delta":{"content":"nk>内部推理"}}]}
                {"choices":[{"delta":{"content":"仍应过滤</mm:th"}}]}
                {"choices":[{"delta":{"content":"ink>最终答案"}}]}
                """);
        List<String> deltas = new ArrayList<>();
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        ChatClient.ChatResponse response = client.chatStream(
                "s1", LlmRequest.of("hi"), deltas::add);

        assertThat(response.answer()).isEqualTo("最终答案");
        assertThat(deltas).containsExactly("最终答案");
    }

    @Test
    void notifiesUsageListenerOncePerCall() {
        serve("""
                {"choices":[{"delta":{"content":"ok"}}]}
                {"usage":{"prompt_tokens":3,"completion_tokens":2}}
                """);
        AtomicReference<ModelUsage> recorded = new AtomicReference<>();
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties,
                (sessionId, usage) -> recorded.set(usage));

        client.chatStream("s1", LlmRequest.of("hi"), delta -> { });

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().promptTokens()).isEqualTo(3);
        assertThat(recorded.get().completionTokens()).isEqualTo(2);
    }

    @Test
    void parsesUsageInNonStreamingDetails() {
        jsonBody = "{\"choices\":[{\"message\":{\"content\":\"你好\"}}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4}}";
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        ChatClient.ChatResponse response = client.chatDetails("s1", LlmRequest.of("你好"));

        assertThat(response.answer()).isEqualTo("你好");
        assertThat(response.usage().totalTokens()).isEqualTo(9);
    }

    @Test
    void requestsStreamUsageOption() {
        List<String> bodies = new ArrayList<>();
        server.createContext("/chat-usage", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        properties.setEndpoint(java.net.URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/chat-usage"));
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        client.chatStream("s1", LlmRequest.of("hi"), delta -> { });

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0))
                .contains("\"stream\":true")
                .contains("include_usage");
    }

    @Test
    void serializesInstructionAndHistoryIntoRequestMessages() {
        List<String> bodies = new ArrayList<>();
        server.createContext("/chat-messages", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        properties.setEndpoint(java.net.URI.create(
                "http://localhost:" + server.getAddress().getPort() + "/chat-messages"));
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        LlmRequest request = new LlmRequest(
                "你是测试助手",
                List.of(
                        LlmMessage.user("今天几号"),
                        LlmMessage.assistant("8月19日"),
                        LlmMessage.user("那明天呢")))
                .withSystemInstruction("你是测试助手");
        client.chatStream("s1", request, delta -> { });

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0))
                .contains("\"role\":\"system\",\"content\":\"你是测试助手\"")
                .contains("\"role\":\"user\",\"content\":\"今天几号\"")
                .contains("\"role\":\"assistant\",\"content\":\"8月19日\"")
                .contains("\"role\":\"user\",\"content\":\"那明天呢\"");
    }

    @Test
    void thinkOnlyStreamFailsExplicitly() {
        serve("""
                {"choices":[{"delta":{"content":"<think>only reasoning"}}]}
                {"choices":[{"delta":{"content":"still thinking</think>"}}]}
                """);
        client = new OpenAiCompatibleChatClient(
                HttpClient.newHttpClient(), new ObjectMapper(), properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.chatStream("s1", LlmRequest.of("hi"), delta -> { }))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("no content");
    }
}
