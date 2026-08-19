package com.github.agentos.server.model;

import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.ModelUsage;
import com.github.agentos.planner.ModelUsageListener;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@link ChatClient} adapter for OpenAI-compatible Chat Completions endpoints.
 *
 * <p>Unlike {@link OpenAiCompatibleModelClient}, this adapter sends the message
 * sequence carried by {@link LlmRequest} without tool definitions or
 * structured-output constraints, so it is suited to the lightweight direct-answer
 * path chosen by intent routing. The system instruction comes from the request
 * (agent identity), not from this client. Supports SSE token streaming, usage
 * parsing and an optional {@link ModelUsageListener} for accounting.</p>
 */
public final class OpenAiCompatibleChatClient implements ChatClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelClientProperties properties;
    private final ModelUsageListener usageListener;

    /**
     * Creates an OpenAI-compatible direct-chat client without usage listener.
     *
     * @param httpClient   configured synchronous HTTP client
     * @param objectMapper application JSON mapper
     * @param properties   endpoint, model and timeout settings
     */
    public OpenAiCompatibleChatClient(
            HttpClient httpClient, ObjectMapper objectMapper, ModelClientProperties properties) {
        this(httpClient, objectMapper, properties, null);
    }

    /**
     * Creates an OpenAI-compatible direct-chat client with a usage listener.
     *
     * @param usageListener optional callback invoked after each successful call
     */
    public OpenAiCompatibleChatClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            ModelClientProperties properties,
            ModelUsageListener usageListener) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.usageListener = usageListener;
        properties.validate();
    }

    @Override
    public String chat(String sessionId, LlmRequest request) {
        return chatDetails(sessionId, request).answer();
    }

    @Override
    public ChatResponse chatDetails(String sessionId, LlmRequest request) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        validateRequest(request);
        HttpRequest httpRequest = createHttpRequest(sessionId, request, false);
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
            JsonNode root = parseJson(response.body());
            String answer = extractAnswer(root);
            ModelUsage usage = extractUsage(root);
            notifyUsage(sessionId, usage);
            LOGGER.info("[chat-call] finished sessionId={} model={} status={} answerChars={} usage={} durationMs={}",
                    sessionId, properties.getEffectiveChatModel(), response.statusCode(),
                    answer.length(), usage == null ? "n/a" : usage.totalTokens(),
                    elapsedMillis(requestStarted));
            return new ChatResponse(answer, usage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Chat request was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "Chat endpoint request failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public ChatResponse chatStream(
            String sessionId, LlmRequest request, Consumer<String> onDelta) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(onDelta, "onDelta must not be null");
        validateRequest(request);
        HttpRequest httpRequest = createHttpRequest(sessionId, request, true);
        long requestStarted = System.nanoTime();
        LOGGER.info("[chat-stream] started sessionId={} model={}",
                sessionId, properties.getEffectiveChatModel());
        StringBuilder answer = new StringBuilder();
        ReasoningFilter filter = new ReasoningFilter(answer, onDelta);
        ModelUsage[] usage = {null};
        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelClientException(
                        "Chat endpoint returned HTTP " + response.statusCode());
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeSseLine(
                        sessionId, line, filter, usage));
            }
            if (answer.isEmpty()) {
                throw new ModelClientException(
                        "Chat stream produced no content; falling back is unavailable");
            }
            notifyUsage(sessionId, usage[0]);
            LOGGER.info("[chat-stream] finished sessionId={} answerChars={} usage={} durationMs={}",
                    sessionId, answer.length(),
                    usage[0] == null ? "n/a" : usage[0].totalTokens(),
                    elapsedMillis(requestStarted));
            return new ChatResponse(answer.toString(), usage[0]);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Chat stream was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "Chat stream request failed: " + exception.getMessage(), exception);
        }
    }

    private void consumeSseLine(
            String sessionId,
            String line,
            ReasoningFilter filter,
            ModelUsage[] usage) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException exception) {
            LOGGER.debug("[chat-stream] skip non-JSON SSE line sessionId={}", sessionId);
            return;
        }
        if (usage[0] == null) {
            ModelUsage parsed = extractUsage(root);
            if (parsed != null) {
                usage[0] = parsed;
            }
        }
        JsonNode content = root.path("choices").path(0).path("delta").path("content");
        if (content.isString() && !content.stringValue().isEmpty()) {
            filter.accept(content.stringValue());
        }
    }

    /**
     * 过滤流式响应中的 {@code <think>} 推理块。
     *
     * <p>部分模型（如 MiniMax-M3）在流式模式下把思考内容以
     * {@code <think>...</think>} 包在 content 里；标签可能跨增量到达，
     * 因此用状态机逐段过滤，只把可见部分回传。</p>
     */
    private static final class ReasoningFilter implements Consumer<String> {

        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";

        private final StringBuilder answer;
        private final Consumer<String> downstream;
        private boolean insideReasoning;

        ReasoningFilter(StringBuilder answer, Consumer<String> downstream) {
            this.answer = answer;
            this.downstream = downstream;
        }

        @Override
        public void accept(String delta) {
            String remaining = delta;
            while (!remaining.isEmpty()) {
                if (insideReasoning) {
                    int close = remaining.indexOf(CLOSE);
                    if (close < 0) {
                        return;
                    }
                    remaining = remaining.substring(close + CLOSE.length());
                    insideReasoning = false;
                } else {
                    int open = remaining.indexOf(OPEN);
                    if (open < 0) {
                        emitVisible(remaining);
                        return;
                    }
                    if (open > 0) {
                        emitVisible(remaining.substring(0, open));
                    }
                    remaining = remaining.substring(open + OPEN.length());
                    insideReasoning = true;
                }
            }
        }

        private void emitVisible(String text) {
            if (text.isEmpty()) {
                return;
            }
            answer.append(text);
            downstream.accept(text);
        }
    }

    private void notifyUsage(String sessionId, ModelUsage usage) {
        if (usage == null || usageListener == null) {
            return;
        }
        try {
            usageListener.onUsage(sessionId, usage);
        } catch (RuntimeException exception) {
            LOGGER.warn("[chat-call] usage listener failed: {}", exception.getMessage());
        }
    }

    private static void validateRequest(LlmRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.messages().isEmpty()) {
            throw new IllegalArgumentException("request must contain at least one message");
        }
    }

    private HttpRequest createHttpRequest(
            String sessionId, LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getEffectiveChatModel());
        body.put("messages", requestMessages(request));
        if (stream) {
            body.put("stream", true);
            // 请求厂商在最后一个 SSE 块返回 usage；不支持的厂商会忽略该选项。
            body.put("stream_options", Map.of("include_usage", true));
        }
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new ModelClientException("Failed to serialize the chat request", exception);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getRequestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serialized, StandardCharsets.UTF_8));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey().trim());
        }
        return builder.build();
    }

    /** 把 LlmRequest 展开为厂商消息数组：可选 system 指令 + user/assistant 序列。 */
    private static List<Map<String, Object>> requestMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        request.instruction().ifPresent(instruction -> messages.add(
                Map.of("role", "system", "content", instruction)));
        for (LlmMessage message : request.messages()) {
            messages.add(Map.of(
                    "role", message.role().name().toLowerCase(
                            java.util.Locale.ROOT),
                    "content", message.content()));
        }
        return messages;
    }

    private JsonNode parseJson(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (JacksonException exception) {
            throw new ModelClientException(
                    "Chat endpoint returned an invalid JSON response body", exception);
        }
    }

    private String extractAnswer(JsonNode root) {
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

    private ModelUsage extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt <= 0 && completion <= 0) {
            return null;
        }
        return new ModelUsage(properties.getEffectiveChatModel(), prompt, completion);
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
