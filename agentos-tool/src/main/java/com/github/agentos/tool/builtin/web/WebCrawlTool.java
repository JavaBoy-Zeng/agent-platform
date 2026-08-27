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

import java.lang.reflect.Array;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用 Firecrawl 遍历网站并抓取页面正文的工具。 */
public final class WebCrawlTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_PAGINATION_REQUESTS = 20;

    private final FirecrawlApiClient apiClient;
    private final Duration crawlTimeout;
    private final Duration pollInterval;
    private final int maxPages;
    private final int maxOutputChars;

    /** 创建 Firecrawl 网站遍历工具。 */
    public WebCrawlTool(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            Duration requestTimeout,
            Duration crawlTimeout,
            Duration pollInterval,
            int maxPages,
            int maxOutputChars) {
        this.apiClient = new FirecrawlApiClient(
                httpClient, objectMapper, baseUrl, apiKey, requestTimeout);
        this.crawlTimeout = positiveDuration(crawlTimeout, "crawlTimeout");
        this.pollInterval = positiveDuration(pollInterval, "pollInterval");
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.maxPages = maxPages;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String name() {
        return "web_crawl";
    }

    @Override
    public String description() {
        return "从指定入口遍历网站并抓取多个页面的 Markdown 正文。适合文档站、帮助中心"
                + "等多页资料；已知单页 URL 时应优先使用 web_fetch。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "url", ToolParameter.ValueType.STRING,
                        "网站入口 URL，必须是 http 或 https", true),
                new ToolParameter(
                        "limit", ToolParameter.ValueType.INTEGER,
                        "最多抓取页面数，默认 20", false),
                new ToolParameter(
                        "max_depth", ToolParameter.ValueType.INTEGER,
                        "从入口开始的最大链接发现深度，默认 2，最大 10", false),
                new ToolParameter(
                        "include_paths", ToolParameter.ValueType.ARRAY,
                        "可选路径规则列表，只抓取匹配的路径", false),
                new ToolParameter(
                        "exclude_paths", ToolParameter.ValueType.ARRAY,
                        "可选路径规则列表，排除匹配的路径", false),
                new ToolParameter(
                        "crawl_entire_domain", ToolParameter.ValueType.BOOLEAN,
                        "是否允许遍历同域的父级和兄弟路径，默认 false", false),
                new ToolParameter(
                        "allow_subdomains", ToolParameter.ValueType.BOOLEAN,
                        "是否允许遍历子域名，默认 false", false),
                new ToolParameter(
                        "only_main_content", ToolParameter.ValueType.BOOLEAN,
                        "是否只提取页面主体内容，默认 true", false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        String url = requiredHttpUrl(call.arguments().get("url"));
        int limit = boundedInteger(call.arguments().get("limit"), "limit", DEFAULT_LIMIT, maxPages);
        int maxDepth = boundedInteger(
                call.arguments().get("max_depth"), "max_depth", DEFAULT_MAX_DEPTH, MAX_DEPTH);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("limit", limit);
        body.put("maxDiscoveryDepth", maxDepth);
        body.put("crawlEntireDomain", optionalBoolean(
                call.arguments().get("crawl_entire_domain"), "crawl_entire_domain", false));
        body.put("allowSubdomains", optionalBoolean(
                call.arguments().get("allow_subdomains"), "allow_subdomains", false));
        List<String> includePaths = optionalStringList(
                call.arguments().get("include_paths"), "include_paths");
        List<String> excludePaths = optionalStringList(
                call.arguments().get("exclude_paths"), "exclude_paths");
        if (!includePaths.isEmpty()) {
            body.put("includePaths", includePaths);
        }
        if (!excludePaths.isEmpty()) {
            body.put("excludePaths", excludePaths);
        }
        body.put("scrapeOptions", Map.of(
                "formats", List.of("markdown"),
                "onlyMainContent", optionalBoolean(
                        call.arguments().get("only_main_content"),
                        "only_main_content", true)));

        try {
            JsonNode start = apiClient.post("/crawl", body);
            if (!start.path("success").asBoolean(true)) {
                return ToolResult.failure(
                        ToolFailureType.UNKNOWN, apiError(start, "web crawl failed to start"));
            }
            String jobId = start.path("id").asString("");
            if (!jobId.matches("[A-Za-z0-9-]+")) {
                return ToolResult.failure(
                        ToolFailureType.TOOL_INTERNAL_ERROR,
                        "Firecrawl returned an invalid crawl job ID");
            }
            return waitForCompletion(context, url, jobId, limit);
        } catch (FirecrawlApiClient.FirecrawlApiException exception) {
            return ToolResult.failure(exception.failureType(), exception.getMessage());
        }
    }

    private ToolResult waitForCompletion(
            ToolContext context, String sourceUrl, String jobId, int limit)
            throws FirecrawlApiClient.FirecrawlApiException {
        long deadline = System.nanoTime() + crawlTimeout.toNanos();
        while (true) {
            if (context.invocation().isCancelled()) {
                return ToolResult.failure(
                        ToolFailureType.CANCELLED, "web crawl was cancelled; jobId=" + jobId);
            }
            JsonNode statusResponse = apiClient.get("/crawl/" + jobId);
            String status = statusResponse.path("status").asString("").toLowerCase();
            if ("completed".equals(status)) {
                return formatCompletedCrawl(sourceUrl, jobId, statusResponse, limit);
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                return ToolResult.failure(
                        ToolFailureType.TRANSIENT,
                        apiError(statusResponse, "web crawl " + status) + "; jobId=" + jobId);
            }
            if (System.nanoTime() >= deadline) {
                return ToolResult.failure(
                        ToolFailureType.TIMEOUT,
                        "web crawl did not complete within " + crawlTimeout.toSeconds()
                                + " seconds; jobId=" + jobId);
            }
            try {
                long remainingMillis = Math.max(
                        1L, (deadline - System.nanoTime()) / 1_000_000L);
                Thread.sleep(Math.min(pollInterval.toMillis(), remainingMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(
                        ToolFailureType.TIMEOUT, "web crawl polling was interrupted; jobId=" + jobId);
            }
        }
    }

    private ToolResult formatCompletedCrawl(
            String sourceUrl, String jobId, JsonNode firstPage, int limit)
            throws FirecrawlApiClient.FirecrawlApiException {
        int total = firstPage.path("total").asInt(0);
        int completed = firstPage.path("completed").asInt(0);
        CrawlOutput output = new CrawlOutput(sourceUrl, maxOutputChars, limit);
        JsonNode page = firstPage;
        Set<String> visitedNextUrls = new HashSet<>();
        int paginationRequests = 0;
        while (page != null) {
            output.appendDocuments(page.path("data"));
            if (output.finished()) {
                break;
            }
            String next = page.path("next").asString("");
            if (next.isBlank() || !visitedNextUrls.add(next)
                    || paginationRequests >= MAX_PAGINATION_REQUESTS) {
                break;
            }
            page = apiClient.getNext(next);
            paginationRequests++;
        }
        return ToolResult.success(
                output.render(total, completed), "",
                Map.of(
                        "jobId", jobId,
                        "sourceUrl", sourceUrl,
                        "status", "completed",
                        "totalPages", total,
                        "completedPages", completed,
                        "returnedPages", output.pageCount(),
                        "truncated", output.truncated()),
                ToolActions.none());
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
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
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
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

    private static List<String> optionalStringList(Object value, String name) {
        if (value == null) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            values.addAll(collection);
        } else if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(Array.get(value, index));
            }
        } else {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        if (values.size() > 50) {
            throw new IllegalArgumentException(name + " must contain at most 50 entries");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(name + " must contain non-blank strings");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static String apiError(JsonNode response, String fallback) {
        String error = response.path("error").asString("");
        return error.isBlank() ? fallback : error;
    }

    /** 在模型上下文预算内逐页格式化抓取结果。 */
    private static final class CrawlOutput {

        private final String sourceUrl;
        private final int maxOutputChars;
        private final int pageLimit;
        private final StringBuilder pages = new StringBuilder();
        private int pageCount;
        private boolean truncated;

        private CrawlOutput(String sourceUrl, int maxOutputChars, int pageLimit) {
            this.sourceUrl = sourceUrl;
            this.maxOutputChars = maxOutputChars;
            this.pageLimit = pageLimit;
        }

        private void appendDocuments(JsonNode documents) {
            for (JsonNode document : documents) {
                if (finished()) {
                    return;
                }
                JsonNode metadata = document.path("metadata");
                String url = metadata.path("sourceURL").asString(
                        metadata.path("url").asString(""));
                String title = metadata.path("title").asString("");
                String markdown = document.path("markdown").asString("").trim();
                StringBuilder item = new StringBuilder()
                        .append(pageCount + 1).append(". ")
                        .append(title.isBlank() ? (url.isBlank() ? "未命名页面" : url) : title)
                        .append('\n');
                if (!url.isBlank()) {
                    item.append("   URL: ").append(url).append('\n');
                }
                if (!markdown.isBlank()) {
                    item.append(markdown).append("\n\n");
                }
                int headerReserve = 160;
                int remaining = maxOutputChars - pages.length() - headerReserve;
                if (remaining <= 0) {
                    truncated = true;
                    return;
                }
                if (item.length() > remaining) {
                    pages.append(item, 0, Math.max(0, remaining));
                    truncated = true;
                    pageCount++;
                    return;
                }
                pages.append(item);
                pageCount++;
            }
        }

        private boolean finished() {
            return truncated || pageCount >= pageLimit;
        }

        private String render(int total, int completed) {
            StringBuilder result = new StringBuilder()
                    .append("网站抓取完成：").append(sourceUrl).append('\n')
                    .append("成功页面：").append(completed)
                    .append(" / 尝试页面：").append(total).append("\n\n")
                    .append(pages);
            if (truncated) {
                result.append("\n... (抓取正文因输出长度限制被截断)");
            } else if (pageCount == 0) {
                result.append("没有返回可用页面正文。");
            }
            return result.toString().stripTrailing();
        }

        private int pageCount() {
            return pageCount;
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
