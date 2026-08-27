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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tavily 网页搜索工具测试（基于本地 HttpServer mock）。 */
class WebSearchToolTest {

    private HttpServer server;
    private WebSearchTool tool;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = """
                    {"results":[
                      {"title":"Java 21","url":"https://example.com/java","content":"Java 21 is LTS","score":0.9},
                      {"title":"Java 22","url":"https://example.com/java22","content":"Java 22 preview","score":0.8}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/rate-limited", exchange -> {
            exchange.sendResponseHeaders(429, 0);
            exchange.close();
        });
        server.createContext("/unavailable", exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
        });
        server.createContext("/forbidden", exchange -> {
            exchange.sendResponseHeaders(403, 0);
            exchange.close();
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
        tool = new WebSearchTool(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), base + "/search",
                "test-key", Duration.ofSeconds(5));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsFormattedResults() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_search", Map.of("query", "java version", "max_results", 2)));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("Java 21")
                .contains("https://example.com/java")
                .contains("Java 22");
    }

    @Test
    void defaultsToFiveResultsWithoutMax() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_search", Map.of("query", "java")));

        assertThat(result.success()).isTrue();
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_search", Map.of("query", " "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeMaxResults() {
        assertThatThrownBy(() -> tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_search", Map.of("query", "x", "max_results", 11))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankApiKey() {
        assertThatThrownBy(() -> new WebSearchTool(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(),
                base, " ", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesRetryableAndAccessHttpErrors() {
        ToolResult rateLimited = searchWithEndpoint("/rate-limited");
        ToolResult unavailable = searchWithEndpoint("/unavailable");
        ToolResult forbidden = searchWithEndpoint("/forbidden");

        assertThat(rateLimited.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(unavailable.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(forbidden.failureType()).isEqualTo(ToolFailureType.ACCESS_DENIED);
    }

    private ToolResult searchWithEndpoint(String path) {
        WebSearchTool endpointTool = new WebSearchTool(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), base + path,
                "test-key", Duration.ofSeconds(5));
        return endpointTool.execute(ToolContexts.testContext(endpointTool),
                new ToolCall("web_search", Map.of("query", "agent")));
    }
}
