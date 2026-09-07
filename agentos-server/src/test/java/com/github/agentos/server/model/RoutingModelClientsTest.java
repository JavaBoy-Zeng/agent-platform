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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingModelClientsTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void resolvesPlatformModelIdButSendsVendorModelId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", "ok")))));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response);
        });
        server.start();

        ModelProviderService providers = new ModelProviderService(
                "memory", null, objectMapper, "");
        providers.create(new ModelProviderService.SaveProviderRequest(
                "DeepSeek", "DEEPSEEK", "CHAT_COMPLETIONS",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                "secret", List.of("deepseek-v4-pro"), "deepseek-v4-pro",
                "JSON_OBJECT", false, ModelProviderService.AdvancedSettings.defaults(), true));
        String platformModelId = providers.snapshot().models().getFirst().id();

        ModelClientProperties defaults = new ModelClientProperties();
        defaults.setConnectTimeout(Duration.ofSeconds(2));
        defaults.setRequestTimeout(Duration.ofSeconds(2));
        ChatClient client = new RoutingModelClients.Chat(
                providers, objectMapper, null, defaults,
                new com.github.agentos.kernel.InMemorySessionService(),
                new com.github.agentos.server.security.InMemoryUserStore(),
                new NonAdminCallLimiter(
                        new com.github.agentos.server.settings.SettingsService.InMemorySettingsService(),
                        objectMapper));

        assertThat(client.chat("session-1", LlmRequest.of("hi").withModel(platformModelId)))
                .isEqualTo("ok");

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertThat(sent.path("model").stringValue()).isEqualTo("deepseek-v4-pro");
        assertThat(sent.path("model").stringValue()).isNotEqualTo(platformModelId);
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
