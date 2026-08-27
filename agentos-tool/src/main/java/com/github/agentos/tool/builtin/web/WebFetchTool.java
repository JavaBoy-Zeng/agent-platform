package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 抓取网页并转为纯文本的工具。
 *
 * <p>只接受文本类响应（HTML/纯文本/JSON/XML），限制响应大小并截断输出，
 * HTML 会去除 script/style 与标签、解码常见实体，得到适合模型消费的正文。</p>
 */
public final class WebFetchTool implements AgentTool {

    private static final long MAX_BYTES = 512 * 1024;

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxOutputChars;

    /** 创建网页抓取工具。 */
    public WebFetchTool(HttpClient httpClient, Duration requestTimeout, int maxOutputChars) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "抓取指定 URL 的网页内容并转为纯文本返回。适合读取文档、博客、API 响应等"
                + "文本资源；不支持图片、视频等二进制内容，超长内容会被截断。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(new ToolParameter(
                "url", ToolParameter.ValueType.STRING,
                "要抓取的完整 URL，必须是 http 或 https", true));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        String url = requiredUrl(call.arguments().get("url"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", "AgentOS-WebFetch/1.0")
                .header("Accept", "text/html,text/plain,application/json,application/xml")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(ToolFailureType.TIMEOUT, "web fetch was interrupted");
        } catch (HttpTimeoutException exception) {
            return ToolResult.failure(
                    ToolFailureType.TIMEOUT, "web fetch timed out for " + url);
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolFailureType.TRANSIENT, "failed to fetch " + url + ": "
                            + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT, "failed to fetch " + url + ": "
                            + exception.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ToolResult.failure(
                    failureTypeForStatus(response.statusCode()),
                    "web fetch returned HTTP " + response.statusCode() + " for " + url);
        }
        String contentType = headerOrEmpty(response, "Content-Type").toLowerCase(Locale.ROOT);
        if (!contentType.isBlank() && !isTextual(contentType)) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT,
                    "unsupported content type: " + contentType);
        }
        byte[] body = bodyWithinLimit(response.body());
        String text = new String(body, charsetOf(contentType));
        return ToolResult.success(
                truncate(htmlToText(text)),
                "", java.util.Map.of("url", url, "chars", text.length()),
                com.github.agentos.tool.api.ToolActions.none());
    }

    private static String headerOrEmpty(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static byte[] bodyWithinLimit(byte[] body) {
        if (body == null) {
            return new byte[0];
        }
        return body.length <= MAX_BYTES
                ? body
                : java.util.Arrays.copyOf(body, (int) MAX_BYTES);
    }

    private static boolean isTextual(String contentType) {
        return contentType.startsWith("text/")
                || contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("javascript");
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

    private static java.nio.charset.Charset charsetOf(String contentType) {
        return StandardCharsets.UTF_8;
    }

    /** 去除 script/style 块、标签与多余空白，并解码常见 HTML 实体。 */
    static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
        return text;
    }

    private String truncate(String value) {
        if (value.length() <= maxOutputChars) {
            return value;
        }
        return value.substring(0, maxOutputChars)
                + "\n... (content truncated at " + maxOutputChars + " characters)";
    }

    private static String requiredUrl(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
        }
        throw new IllegalArgumentException("url must be a valid http(s) URL");
    }
}
