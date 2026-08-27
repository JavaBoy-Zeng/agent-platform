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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Firecrawl 网站遍历抓取工具测试。 */
class WebCrawlToolTest {

    private HttpServer server;
    private WebCrawlTool tool;
    private String baseUrl;
    private final AtomicInteger statusRequests = new AtomicInteger();
    private final AtomicReference<String> startBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/crawl", exchange -> {
            String receivedBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            startBody.set(receivedBody);
            String jobId = receivedBody.contains("fail.example.com") ? "failed-job" : "job-1";
            respond(exchange, 200,
                    "{\"success\":true,\"id\":\"" + jobId + "\"}");
        });
        server.createContext("/v2/crawl/job-1", exchange -> {
            if (statusRequests.incrementAndGet() == 1) {
                respond(exchange, 200,
                        "{\"status\":\"scraping\",\"total\":2,\"completed\":1,\"data\":[]}");
                return;
            }
            respond(exchange, 200, """
                    {"status":"completed","total":2,"completed":2,"data":[
                      {"markdown":"# Guide\\nInstall AgentOS.","metadata":{"title":"Guide","sourceURL":"https://example.com/docs/guide"}},
                      {"markdown":"# API\\nCall the API.","metadata":{"title":"API","sourceURL":"https://example.com/docs/api"}}
                    ]}
                    """);
        });
        server.createContext("/v2/crawl/failed-job", exchange -> respond(
                exchange, 200,
                "{\"status\":\"failed\",\"error\":\"crawl worker unavailable\"}"));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        tool = new WebCrawlTool(
                HttpClient.newHttpClient(), new ObjectMapper(), baseUrl, "test-key",
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(1),
                100, 20_000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void startsPollsAndReturnsCrawledPages() {
        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_crawl", Map.of(
                        "url", "https://example.com/docs",
                        "limit", 10,
                        "max_depth", 3,
                        "include_paths", List.of("/docs/**"),
                        "exclude_paths", List.of("/docs/private/**"),
                        "crawl_entire_domain", true)));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("网站抓取完成")
                .contains("Guide")
                .contains("Install AgentOS")
                .contains("https://example.com/docs/api");
        assertThat(result.metadata())
                .containsEntry("jobId", "job-1")
                .containsEntry("returnedPages", 2)
                .containsEntry("completedPages", 2);
        assertThat(statusRequests.get()).isEqualTo(2);
        assertThat(startBody.get())
                .contains("\"limit\":10")
                .contains("\"maxDiscoveryDepth\":3")
                .contains("\"includePaths\":[\"/docs/**\"]")
                .contains("\"excludePaths\":[\"/docs/private/**\"]")
                .contains("\"crawlEntireDomain\":true")
                .contains("\"onlyMainContent\":true");
    }

    @Test
    void rejectsInvalidLimitsAndPathLists() {
        assertThatThrownBy(() -> tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_crawl", Map.of(
                        "url", "https://example.com", "limit", 101))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_crawl", Map.of(
                        "url", "https://example.com",
                        "include_paths", List.of("/docs", 12)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesFailedJobAsTransient() {
        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("web_crawl", Map.of("url", "https://fail.example.com")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(result.error()).contains("crawl worker unavailable");
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
