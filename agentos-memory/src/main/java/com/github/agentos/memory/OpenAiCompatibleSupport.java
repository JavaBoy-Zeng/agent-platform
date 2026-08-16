package com.github.agentos.memory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** OpenAI-compatible JSON HTTP 调用的模块内共享实现。 */
final class OpenAiCompatibleSupport {

    private OpenAiCompatibleSupport() {
    }

    static JsonNode post(
            HttpClient client,
            ObjectMapper mapper,
            URI endpoint,
            String apiKey,
            Duration timeout,
            Map<String, Object> body) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        validateEndpoint(endpoint);
        requirePositive(timeout, "timeout");

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new MemoryAdapterException("failed to serialize memory model request", exception);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        try {
            HttpResponse<String> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MemoryAdapterException(
                        "memory model endpoint returned HTTP " + response.statusCode() + errorDetail(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MemoryAdapterException("memory model request was interrupted", exception);
        } catch (IOException | JacksonException exception) {
            throw new MemoryAdapterException("memory model request failed: " + exception.getMessage(), exception);
        }
    }

    static String chatContent(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (!content.isString() || content.asString().isBlank()) {
            throw new MemoryAdapterException("memory model response does not contain choices[0].message.content");
        }
        return stripJsonFence(content.asString());
    }

    static JsonNode parseJson(ObjectMapper mapper, String content) {
        try {
            return mapper.readTree(content);
        } catch (JacksonException exception) {
            throw new MemoryAdapterException("memory model returned invalid JSON", exception);
        }
    }

    static void validateModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }

    static void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getScheme() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URI");
        }
    }

    static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String stripJsonFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) return trimmed;
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        String normalized = body.replaceAll("\\s+", " ").trim();
        return ": " + normalized.substring(0, Math.min(normalized.length(), 500));
    }
}
