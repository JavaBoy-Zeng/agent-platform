package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调用 Tavily API 的网页搜索工具。
 *
 * <p>只有配置了 {@code agentos.tools.web-search.api-key} 时才会注册；
 * 返回带标题、URL 与摘要的结构化搜索结果。</p>
 */
public final class WebSearchTool implements AgentTool {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final Duration requestTimeout;

    /** 创建 Tavily 网页搜索工具。 */
    public WebSearchTool(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String endpoint,
            String apiKey,
            Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null").trim();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (this.apiKey.isEmpty()) {
            throw new IllegalArgumentException("web search API key must not be blank");
        }
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "搜索互联网并返回带标题、链接和摘要的结果列表。适合回答需要最新信息、"
                + "实时数据或项目外知识的问题。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "query", ToolParameter.ValueType.STRING,
                        "搜索关键词或完整问题", true),
                new ToolParameter(
                        "max_results", ToolParameter.ValueType.INTEGER,
                        "返回结果数量上限，默认 5，最大 10", false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        String query = requiredString(call.arguments().get("query"), "query");
        int maxResults = maxResults(call.arguments().get("max_results"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"query\":" + quote(query)
                                + ",\"max_results\":" + maxResults + "}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(ToolFailureType.TIMEOUT, "web search was interrupted");
        } catch (HttpTimeoutException exception) {
            return ToolResult.failure(ToolFailureType.TIMEOUT, "web search request timed out");
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolFailureType.TRANSIENT,
                    "web search request failed: " + exception.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ToolResult.failure(
                    failureTypeForStatus(response.statusCode()),
                    "web search returned HTTP " + response.statusCode());
        }
        return parseResults(response.body(), maxResults);
    }

    private ToolResult parseResults(String body, int maxResults) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            return ToolResult.failure(
                    ToolFailureType.TOOL_INTERNAL_ERROR, "web search returned invalid JSON");
        }
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (JsonNode result : root.path("results")) {
            if (count >= maxResults) {
                break;
            }
            String title = result.path("title").asString("");
            String url = result.path("url").asString("");
            String content = result.path("content").asString("");
            output.append(count + 1).append(". ").append(title)
                    .append("\n   ").append(url)
                    .append("\n   ").append(content.isBlank() ? "" : abbreviate(content))
                    .append('\n');
            count++;
        }
        if (count == 0) {
            return ToolResult.success("没有找到相关结果。");
        }
        return ToolResult.success(
                output.toString().stripTrailing(),
                "", Map.of("resultCount", count), com.github.agentos.tool.api.ToolActions.none());
    }

    private static ToolFailureType failureTypeForStatus(int statusCode) {
        if (statusCode == 408) {
            return ToolFailureType.TIMEOUT;
        }
        if (statusCode == 429 || statusCode >= 500) {
            return ToolFailureType.TRANSIENT;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ToolFailureType.ACCESS_DENIED;
        }
        return ToolFailureType.NOT_FOUND;
    }

    private static int maxResults(Object configured) {
        if (configured == null) {
            return 5;
        }
        if (configured instanceof Number number) {
            int value = number.intValue();
            if (value < 1 || value > 10) {
                throw new IllegalArgumentException("max_results must be between 1 and 10");
            }
            return value;
        }
        throw new IllegalArgumentException("max_results must be an integer");
    }

    private static String requiredString(Object value, String name) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(name + " must be a non-blank string");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static String abbreviate(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 400 ? compact : compact.substring(0, 400) + "...";
    }
}
