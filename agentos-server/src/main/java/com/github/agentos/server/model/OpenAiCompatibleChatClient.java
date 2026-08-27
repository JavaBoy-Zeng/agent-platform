package com.github.agentos.server.model;

import com.github.agentos.kernel.ModelUsage;
import com.github.agentos.planner.ChatClient;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern REASONING_BLOCK = Pattern.compile(
            "(?is)<(?:[a-z0-9_.-]+:)?think\\b[^>]*>.*?"
                    + "</(?:[a-z0-9_.-]+:)?think\\s*>");
    private static final Pattern REASONING_OPEN_TAG = Pattern.compile(
            "(?i)<(?:[a-z0-9_.-]+:)?think(?:\\s[^>]*)?>");
    private static final Pattern REASONING_CLOSE_TAG = Pattern.compile(
            "(?i)</(?:[a-z0-9_.-]+:)?think\\s*>");

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
            filter.finish();
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
     * 过滤流式响应中的 {@code <think>} / {@code <mm:think>} 推理块。
     *
     * <p>标签可能跨多个 SSE 增量到达，因此保留未闭合标签并用状态机过滤；
     * 模型推理不会进入回答文本或下游事件。</p>
     */
    private static final class ReasoningFilter implements Consumer<String> {

        private final StringBuilder answer;
        private final Consumer<String> downstream;
        private final StringBuilder pending = new StringBuilder();
        private boolean insideReasoning;

        ReasoningFilter(StringBuilder answer, Consumer<String> downstream) {
            this.answer = answer;
            this.downstream = downstream;
        }

        @Override
        public void accept(String delta) {
            pending.append(delta);
            drain(false);
        }

        /** 流结束后刷新尚未构成标签的文本。 */
        void finish() {
            drain(true);
        }

        private void drain(boolean endOfInput) {
            while (!pending.isEmpty()) {
                if (insideReasoning) {
                    int tagStart = pending.indexOf("<");
                    if (tagStart < 0) {
                        pending.setLength(0);
                        return;
                    }
                    if (tagStart > 0) {
                        pending.delete(0, tagStart);
                    }
                    int tagEnd = pending.indexOf(">");
                    if (tagEnd < 0) {
                        if (endOfInput) {
                            pending.setLength(0);
                        }
                        return;
                    }
                    String tag = pending.substring(0, tagEnd + 1);
                    pending.delete(0, tagEnd + 1);
                    if (REASONING_CLOSE_TAG.matcher(tag).matches()) {
                        insideReasoning = false;
                    }
                    continue;
                }

                int tagStart = pending.indexOf("<");
                if (tagStart < 0) {
                    emitVisible(pending.toString());
                    pending.setLength(0);
                    return;
                }
                if (tagStart > 0) {
                    emitVisible(pending.substring(0, tagStart));
                    pending.delete(0, tagStart);
                }
                int tagEnd = pending.indexOf(">");
                if (tagEnd < 0) {
                    if (endOfInput) {
                        emitVisible(pending.toString());
                        pending.setLength(0);
                    }
                    return;
                }
                String tag = pending.substring(0, tagEnd + 1);
                pending.delete(0, tagEnd + 1);
                if (REASONING_OPEN_TAG.matcher(tag).matches()) {
                    insideReasoning = true;
                } else if (!REASONING_CLOSE_TAG.matcher(tag).matches()) {
                    emitVisible(tag);
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
        if (properties.isReasoningSplit()) {
            body.put("reasoning_split", true);
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
                message("system", instruction)));
        for (LlmMessage message : request.messages()) {
            messages.add(message(
                    message.role().name().toLowerCase(java.util.Locale.ROOT),
                    message.content()));
        }
        return messages;
    }

    /** 构造键序固定的消息体（role 在前、content 在后）；Map.of 的迭代顺序不稳定。 */
    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
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
                return requireVisibleAnswer(stripReasoning(text.toString()));
            }
        }
        if (content.isString() && !content.stringValue().isBlank()) {
            return requireVisibleAnswer(stripReasoning(content.stringValue()));
        }
        throw new ModelClientException("Chat response content was not usable text");
    }

    /**
     * 删除非流式响应中混入 content 的推理块。
     *
     * <p>除标准 {@code <think>} 外，也接受带厂商命名空间的 {@code <mm:think>}。
     * 某些兼容接口只返回结束标签；此时结束标签之前的内容按推理处理，避免泄漏到最终回答。</p>
     */
    private static String stripReasoning(String value) {
        String visible = REASONING_BLOCK.matcher(value).replaceAll("");
        Matcher danglingClose = REASONING_CLOSE_TAG.matcher(visible);
        int lastCloseEnd = -1;
        while (danglingClose.find()) {
            lastCloseEnd = danglingClose.end();
        }
        if (lastCloseEnd >= 0) {
            visible = visible.substring(lastCloseEnd);
        }
        Matcher danglingOpen = REASONING_OPEN_TAG.matcher(visible);
        if (danglingOpen.find()) {
            visible = visible.substring(0, danglingOpen.start());
        }
        return REASONING_CLOSE_TAG.matcher(visible).replaceAll("").strip();
    }

    private static String requireVisibleAnswer(String value) {
        if (value.isBlank()) {
            throw new ModelClientException("Chat response contained reasoning but no final answer");
        }
        return value;
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
