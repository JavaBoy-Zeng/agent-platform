package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolActions;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用 Firecrawl 发现网站内部链接的工具。 */
public final class WebMapTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 100;

    private final FirecrawlApiClient apiClient;
    private final int maxLinks;
    private final int maxOutputChars;

    /** 创建 Firecrawl URL Map 工具。 */
    public WebMapTool(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            int maxLinks,
            int maxOutputChars) {
        this.apiClient = new FirecrawlApiClient(
                httpClient, objectMapper, baseUrl, apiKey, requestTimeout);
        if (maxLinks <= 0) {
            throw new IllegalArgumentException("maxLinks must be positive");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.maxLinks = maxLinks;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String name() {
        return "web_map";
    }

    @Override
    public String description() {
        return "发现指定网站中的内部链接并返回 URL、标题和描述；不会抓取每个页面正文。"
                + "适合在 web_crawl 或 web_fetch 前快速了解网站结构。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "url", ToolParameter.ValueType.STRING,
                        "要发现链接的网站入口 URL，必须是 http 或 https", true),
                new ToolParameter(
                        "search", ToolParameter.ValueType.STRING,
                        "可选关键词；提供后按与关键词的相关性排序链接", false),
                new ToolParameter(
                        "limit", ToolParameter.ValueType.INTEGER,
                        "最多返回多少个链接，默认 100", false),
                new ToolParameter(
                        "include_subdomains", ToolParameter.ValueType.BOOLEAN,
                        "是否包含子域名，默认 false", false),
                new ToolParameter(
                        "ignore_query_parameters", ToolParameter.ValueType.BOOLEAN,
                        "是否忽略带查询参数的重复 URL，默认 true", false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        String url = requiredHttpUrl(call.arguments().get("url"));
        int limit = boundedInteger(call.arguments().get("limit"), "limit", DEFAULT_LIMIT, maxLinks);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("limit", limit);
        body.put("includeSubdomains", optionalBoolean(
                call.arguments().get("include_subdomains"), "include_subdomains", false));
        body.put("ignoreQueryParameters", optionalBoolean(
                call.arguments().get("ignore_query_parameters"),
                "ignore_query_parameters", true));
        String search = optionalString(call.arguments().get("search"), "search");
        if (!search.isBlank()) {
            body.put("search", search);
        }

        try {
            JsonNode response = apiClient.post("/map", body);
            if (!response.path("success").asBoolean(true)) {
                return ToolResult.failure(
                        ToolFailureType.UNKNOWN, apiError(response, "web map failed"));
            }
            return formatLinks(url, response.path("links"), limit);
        } catch (FirecrawlApiClient.FirecrawlApiException exception) {
            return ToolResult.failure(exception.failureType(), exception.getMessage());
        }
    }

    private ToolResult formatLinks(String sourceUrl, JsonNode links, int limit) {
        StringBuilder output = new StringBuilder("站内链接：").append(sourceUrl).append('\n');
        int count = 0;
        boolean truncated = false;
        for (JsonNode link : links) {
            if (count >= limit) {
                break;
            }
            String url;
            String title = "";
            String description = "";
            if (link.isTextual()) {
                url = link.asString("");
            } else {
                url = link.path("url").asString("");
                title = link.path("title").asString("");
                description = link.path("description").asString("");
            }
            if (url.isBlank()) {
                continue;
            }
            StringBuilder item = new StringBuilder()
                    .append(count + 1).append(". ")
                    .append(title.isBlank() ? url : title)
                    .append('\n').append("   ").append(url).append('\n');
            if (!description.isBlank()) {
                item.append("   ").append(compact(description)).append('\n');
            }
            if (output.length() + item.length() > maxOutputChars) {
                truncated = true;
                break;
            }
            output.append(item);
            count++;
        }
        if (truncated) {
            output.append("... (链接列表因输出长度限制被截断)");
        }
        if (count == 0) {
            output.append("没有发现可用链接。");
        }
        return ToolResult.success(
                output.toString().stripTrailing(), "",
                Map.of(
                        "sourceUrl", sourceUrl,
                        "linkCount", count,
                        "truncated", truncated),
                ToolActions.none());
    }

    private static String requiredHttpUrl(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("url must be a non-blank http(s) URL");
        }
        URI uri;
        try {
            uri = URI.create(text.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("url must be a valid http(s) URL", exception);
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http")
                || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url must be a valid http(s) URL");
        }
        return uri.toString();
    }

    private static int boundedInteger(
            Object value, String name, int defaultValue, int maximum) {
        if (value == null) {
            return Math.min(defaultValue, maximum);
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int result = number.intValue();
        if (result < 1 || result > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + maximum);
        }
        return result;
    }

    private static boolean optionalBoolean(Object value, String name, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalArgumentException(name + " must be a boolean");
    }

    private static String optionalString(Object value, String name) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.trim();
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static String compact(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    private static String apiError(JsonNode response, String fallback) {
        String error = response.path("error").asString("");
        return error.isBlank() ? fallback : error;
    }
}
