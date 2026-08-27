package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Firecrawl 站内链接发现工具测试。 */
class WebMapToolTest {

    private HttpServer server;
    private WebMapTool tool;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/map", exchange -> {
            String receivedBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBody.set(receivedBody);
            if (receivedBody.contains("rate-limit.example.com")) {
                respond(exchange, 429, "{\"error\":\"too many requests\"}");
                return;
            }
            respond(exchange, 200, """
                    {"success":true,"links":[
                      {"url":"https://example.com/docs","title":"Docs","description":"Product documentation"},
                      "https://example.com/pricing"
                    ]}
                    """);
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        tool = new WebMapTool(
                HttpClient.newHttpClient(), new ObjectMapper(), baseUrl, "test-key",
                Duration.ofSeconds(5), 200, 12_000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsFormattedLinksAndSendsMapOptions() {
        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_map", Map.of(
                        "url", "https://example.com",
                        "search", "docs",
                        "limit", 20,
                        "include_subdomains", true)));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("Docs")
                .contains("https://example.com/docs")
                .contains("https://example.com/pricing");
        assertThat(result.metadata()).containsEntry("linkCount", 2);
        assertThat(requestBody.get())
                .contains("\"search\":\"docs\"")
                .contains("\"limit\":20")
                .contains("\"includeSubdomains\":true")
                .contains("\"ignoreQueryParameters\":true");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_map", Map.of("url", "ftp://example.com"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_map", Map.of(
                        "url", "https://example.com", "limit", 201))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesRateLimitAsTransient() {
        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_map", Map.of("url", "https://rate-limit.example.com")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(result.error()).contains("too many requests");
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
