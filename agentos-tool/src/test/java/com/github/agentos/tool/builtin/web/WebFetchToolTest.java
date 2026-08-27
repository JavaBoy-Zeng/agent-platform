package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网页抓取工具测试（基于本地 HttpServer mock）。 */
class WebFetchToolTest {

    private HttpServer server;
    private WebFetchTool tool;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/html", exchange -> {
            byte[] body = """
                    <html><head><style>.x{color:red}</style><script>alert(1)</script></head>
                    <body><h1>Hello&nbsp;AgentOS</h1><p>line1 &amp; line2</p></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/json", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/binary", exchange -> {
            byte[] body = new byte[]{0x00, 0x01, 0x02};
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
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
        tool = new WebFetchTool(
                java.net.http.HttpClient.newHttpClient(), Duration.ofSeconds(5), 12000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void convertsHtmlToPlainText() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", base + "/html")));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("Hello AgentOS")
                .contains("line1 & line2")
                .doesNotContain("<h1>")
                .doesNotContain("alert")
                .doesNotContain("color:red");
    }

    @Test
    void passesJsonThrough() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", base + "/json")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("\"ok\":true");
    }

    @Test
    void rejectsBinaryContent() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", base + "/binary")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unsupported content type");
    }

    @Test
    void reportsHttpErrors() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", base + "/missing")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("HTTP 404");
        assertThat(result.failureType()).isEqualTo(ToolFailureType.NOT_FOUND);
    }

    @Test
    void classifiesRetryableAndAccessHttpErrors() {
        ToolResult rateLimited = fetch("/rate-limited");
        ToolResult unavailable = fetch("/unavailable");
        ToolResult forbidden = fetch("/forbidden");

        assertThat(rateLimited.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(unavailable.failureType()).isEqualTo(ToolFailureType.TRANSIENT);
        assertThat(forbidden.failureType()).isEqualTo(ToolFailureType.ACCESS_DENIED);
    }

    @Test
    void rejectsNonHttpUrl() {
        assertThatThrownBy(() -> tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", "ftp://example.com"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ToolResult fetch(String path) {
        return tool.execute(ToolContexts.testContext(tool),
                new ToolCall("web_fetch", Map.of("url", base + path)));
    }
}
