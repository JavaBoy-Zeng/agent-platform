package com.github.agentos.server.model;

import com.github.agentos.planner.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link ChatClient} adapter for OpenAI-compatible Chat Completions endpoints.
 *
 * <p>Unlike {@link OpenAiCompatibleModelClient}, this adapter sends a single
 * system-plus-user message pair without tool definitions or structured-output
 * constraints, so it is suited to the lightweight direct-answer path chosen by
 * intent routing.</p>
 */
public final class OpenAiCompatibleChatClient implements ChatClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private static final String SYSTEM_PROMPT = """
            你是一个高效的中文助手。直接、准确地回答用户问题，不要编造事实。
            如果问题需要实时信息、文件操作或外部工具才能回答，请明确说明你无法获取这类信息，
            并建议用户描述完整任务后重试。
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelClientProperties properties;

    /**
     * Creates an OpenAI-compatible direct-chat client.
     *
     * @param httpClient   configured synchronous HTTP client
     * @param objectMapper application JSON mapper
     * @param properties   endpoint, model and timeout settings
     */
    public OpenAiCompatibleChatClient(
            HttpClient httpClient, ObjectMapper objectMapper, ModelClientProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        properties.validate();
    }

    @Override
    public String chat(String sessionId, String userMessage) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        HttpRequest httpRequest = createHttpRequest(sessionId, userMessage);
        long requestStarted = System.nanoTime();
        LOGGER.info("[chat-call] started sessionId={} model={} endpoint={}",
                sessionId, properties.getEffectiveChatModel(), properties.getEndpoint());
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelClientException(
                        "Chat endpoint returned HTTP " + response.statusCode()
                                + errorDetail(response.body()));
            }
            String answer = parseAnswer(response.body());
            LOGGER.info("[chat-call] finished sessionId={} model={} status={} answerChars={} durationMs={}",
                    sessionId, properties.getEffectiveChatModel(), response.statusCode(),
                    answer.length(), elapsedMillis(requestStarted));
            return answer;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Chat request was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "Chat endpoint request failed: " + exception.getMessage(), exception);
        }
    }

    private HttpRequest createHttpRequest(String sessionId, String userMessage) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getEffectiveChatModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", userMessage))));
        } catch (JacksonException exception) {
            throw new ModelClientException("Failed to serialize the chat request", exception);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getRequestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey().trim());
        }
        return builder.build();
    }

    private String parseAnswer(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JacksonException exception) {
            throw new ModelClientException(
                    "Chat endpoint returned an invalid JSON response body", exception);
        }

        JsonNode message = root.path("choices").path(0).path("message");
        String refusal = textValue(message.get("refusal"));
        if (refusal != null) {
            throw new ModelClientException("Model refused to answer: " + refusal);
        }
        JsonNode content = message.get("content");
        if (content == null || content.isNull() || content.isMissingNode()) {
            throw new ModelClientException("Chat response did not contain message content");
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                String partText = textValue(part.get("text"));
                if (partText != null) {
                    text.append(partText);
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
        }
        if (content.isString() && !content.stringValue().isBlank()) {
            return content.stringValue();
        }
        throw new ModelClientException("Chat response content was not usable text");
    }

    private String errorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            String message = textValue(
                    objectMapper.readTree(responseBody).path("error").get("message"));
            return message == null ? "" : ": " + abbreviate(message);
        } catch (JacksonException ignored) {
            return "";
        }
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isBlank()
                ? node.stringValue() : null;
    }

    private static String abbreviate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
