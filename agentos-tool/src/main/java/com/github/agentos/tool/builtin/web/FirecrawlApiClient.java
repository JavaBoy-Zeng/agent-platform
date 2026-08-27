package com.github.agentos.tool.builtin.web;

import com.github.agentos.tool.api.ToolFailureType;
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
import java.util.Objects;

/** Firecrawl v2 API 的轻量 HTTP 客户端，供内置网页工具复用。 */
final class FirecrawlApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiRoot;
    private final String apiKey;
    private final Duration requestTimeout;

    FirecrawlApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.apiRoot = apiRoot(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    JsonNode post(String path, Object body) throws FirecrawlApiException {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new FirecrawlApiException(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    "failed to encode Firecrawl request", exception);
        }
        HttpRequest request = requestBuilder(resolvePath(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    JsonNode get(String path) throws FirecrawlApiException {
        return send(requestBuilder(resolvePath(path)).GET().build());
    }

    JsonNode getNext(String nextUrl) throws FirecrawlApiException {
        URI next;
        try {
            String value = Objects.requireNonNull(nextUrl, "nextUrl").trim();
            URI candidate = URI.create(value);
            next = candidate.isAbsolute()
                    ? candidate
                    : URI.create(apiRoot.toString() + "/").resolve(candidate);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new FirecrawlApiException(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    "Firecrawl returned an invalid pagination URL", exception);
        }
        if (!sameOrigin(apiRoot, next)) {
            throw new FirecrawlApiException(
                    ToolFailureType.SECURITY_DENIED,
                    "Firecrawl pagination URL points to a different origin");
        }
        return send(requestBuilder(next).GET().build());
    }

    private JsonNode send(HttpRequest request) throws FirecrawlApiException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FirecrawlApiException(
                    ToolFailureType.TIMEOUT, "Firecrawl request was interrupted", exception);
        } catch (HttpTimeoutException exception) {
            throw new FirecrawlApiException(
                    ToolFailureType.TIMEOUT, "Firecrawl request timed out", exception);
        } catch (IOException exception) {
            throw new FirecrawlApiException(
                    ToolFailureType.TRANSIENT,
                    "Firecrawl request failed: " + safeMessage(exception), exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonNode body = parseErrorBody(response.body());
            String detail = firstText(body, "error", "message");
            String message = "Firecrawl returned HTTP " + response.statusCode();
            if (!detail.isBlank()) {
                message += ": " + abbreviate(detail, 500);
            }
            throw new FirecrawlApiException(failureTypeForStatus(response.statusCode()), message);
        }
        JsonNode body = parseBody(response.body());
        if (body == null) {
            throw new FirecrawlApiException(
                    ToolFailureType.TOOL_INTERNAL_ERROR, "Firecrawl returned invalid JSON");
        }
        return body;
    }

    private JsonNode parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private JsonNode parseBody(String body) throws FirecrawlApiException {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException exception) {
            throw new FirecrawlApiException(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    "Firecrawl returned invalid JSON", exception);
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "AgentOS-Firecrawl/1.0");
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private URI resolvePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Firecrawl API path must start with /");
        }
        return URI.create(apiRoot.toString() + path);
    }

    private static URI apiRoot(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Firecrawl base URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Firecrawl base URL is invalid", exception);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Firecrawl base URL must be an http(s) origin");
        }
        String normalized = uri.toString().replaceAll("/+$", "");
        return URI.create(normalized.endsWith("/v2") ? normalized : normalized + "/v2");
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static ToolFailureType failureTypeForStatus(int statusCode) {
        if (statusCode == 408 || statusCode == 504) {
            return ToolFailureType.TIMEOUT;
        }
        if (statusCode == 429 || statusCode >= 500 || statusCode == 409) {
            return ToolFailureType.TRANSIENT;
        }
        if (statusCode == 401 || statusCode == 402 || statusCode == 403) {
            return ToolFailureType.ACCESS_DENIED;
        }
        if (statusCode == 400 || statusCode == 422) {
            return ToolFailureType.INVALID_ARGUMENT;
        }
        if (statusCode == 404) {
            return ToolFailureType.NOT_FOUND;
        }
        return ToolFailureType.UNKNOWN;
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            String value = node.path(name).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String abbreviate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    /** 带运行时失败分类的 Firecrawl 调用异常。 */
    static final class FirecrawlApiException extends Exception {

        private final ToolFailureType failureType;

        FirecrawlApiException(ToolFailureType failureType, String message) {
            super(message);
            this.failureType = Objects.requireNonNull(failureType, "failureType");
        }

        FirecrawlApiException(
                ToolFailureType failureType, String message, Throwable cause) {
            super(message, cause);
            this.failureType = Objects.requireNonNull(failureType, "failureType");
        }

        ToolFailureType failureType() {
            return failureType;
        }
    }
}
