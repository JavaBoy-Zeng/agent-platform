package com.github.agentos.server.model;

import com.github.agentos.kernel.ModelUsage;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.ModelUsageListener;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.planner.flow.LlmToolDefinition;
import com.github.agentos.tool.api.ToolCall;
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
import java.util.stream.Collectors;
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
        String model = modelFor(request);
        HttpRequest httpRequest = createHttpRequest(sessionId, request, false);
        long requestStarted = System.nanoTime();
        LOGGER.info("[chat-call] started sessionId={} model={} endpoint={}",
                sessionId, model, properties.getEndpoint());
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelClientException(
                        "Chat endpoint returned HTTP " + response.statusCode()
                                + errorDetail(response.body()));
            }
            JsonNode root = parseJson(response.body());
            AnswerPayload payload = extractAnswer(root);
            ModelUsage usage = extractUsage(root, model);
            notifyUsage(sessionId, usage);
            LOGGER.info("[chat-call] finished sessionId={} model={} status={} answerChars={} reasoningChars={} usage={} durationMs={}",
                    sessionId, model, response.statusCode(),
                    payload.answer().length(),
                    payload.reasoningContent() == null ? 0 : payload.reasoningContent().length(),
                    usage == null ? "n/a" : usage.totalTokens(),
                    elapsedMillis(requestStarted));
            return ChatResponse.withReasoning(
                    payload.answer(), payload.reasoningContent(), usage);
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
        String model = modelFor(request);
        HttpRequest httpRequest = createHttpRequest(sessionId, request, true);
        long requestStarted = System.nanoTime();
        LOGGER.info("[chat-stream] started sessionId={} model={}",
                sessionId, model);
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ReasoningFilter filter = new ReasoningFilter(answer, onDelta);
        ModelUsage[] usage = {null};
        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody;
                try (Stream<String> errorLines = response.body()) {
                    errorBody = errorLines.collect(Collectors.joining("\n"));
                }
                throw new ModelClientException(
                        "Chat endpoint returned HTTP " + response.statusCode()
                                + errorDetail(errorBody));
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeSseLine(
                        sessionId, line, filter, reasoning, usage, model));
            }
            filter.finish();
            if (answer.isEmpty()) {
                throw new ModelClientException(
                        "Chat stream produced no content; falling back is unavailable");
            }
            notifyUsage(sessionId, usage[0]);
            String reasoningText = reasoning.length() == 0 ? null : reasoning.toString();
            LOGGER.info("[chat-stream] finished sessionId={} answerChars={} reasoningChars={} usage={} durationMs={}",
                    sessionId, answer.length(),
                    reasoningText == null ? 0 : reasoningText.length(),
                    usage[0] == null ? "n/a" : usage[0].totalTokens(),
                    elapsedMillis(requestStarted));
            return ChatResponse.withReasoning(answer.toString(), reasoningText, usage[0]);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Chat stream was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "Chat stream request failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public ToolCallResponse chatWithTools(
            String sessionId, LlmRequest request, Consumer<String> onDelta) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(onDelta, "onDelta must not be null");
        validateRequest(request);
        if (request.tools().isEmpty()) {
            throw new IllegalArgumentException("chatWithTools requires a non-empty tools list");
        }
        String model = modelFor(request);
        HttpRequest httpRequest = createHttpRequest(sessionId, request, true);
        long requestStarted = System.nanoTime();
        LOGGER.info("[chat-tools] started sessionId={} model={} toolCount={}",
                sessionId, model, request.tools().size());
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ReasoningFilter filter = new ReasoningFilter(answer, onDelta);
        ToolCallAccumulator toolCalls = new ToolCallAccumulator();
        ModelUsage[] usage = {null};
        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody;
                try (Stream<String> errorLines = response.body()) {
                    errorBody = errorLines.collect(Collectors.joining("\n"));
                }
                throw new ModelClientException(
                        "Chat endpoint returned HTTP " + response.statusCode()
                                + errorDetail(errorBody));
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeToolCallSseLine(
                        sessionId, line, model, filter, reasoning, toolCalls, usage));
            }
            filter.finish();
            notifyUsage(sessionId, usage[0]);
            String reasoningText = reasoning.length() == 0 ? null : reasoning.toString();
            if (!toolCalls.isEmpty()) {
                ToolCallAccumulator.Completed first = toolCalls.firstCompleted();
                LOGGER.info(
                        "[chat-tools] finished sessionId={} toolCall={} argsChars={} reasoningChars={} usage={} durationMs={}",
                        sessionId, first.name(), first.argumentsJson().length(),
                        reasoningText == null ? 0 : reasoningText.length(),
                        usage[0] == null ? "n/a" : usage[0].totalTokens(),
                        elapsedMillis(requestStarted));
                return ToolCallResponse.callWithReasoning(
                        new ToolCall(first.name(),
                                parseToolCallArguments(first.name(), first.argumentsJson())),
                        first.id(),
                        reasoningText,
                        usage[0]);
            }
            if (answer.isEmpty()) {
                throw new ModelClientException(
                        "Chat stream produced neither tool calls nor content");
            }
            LOGGER.info("[chat-tools] finished sessionId={} answerChars={} reasoningChars={} usage={} durationMs={}",
                    sessionId, answer.length(),
                    reasoningText == null ? 0 : reasoningText.length(),
                    usage[0] == null ? "n/a" : usage[0].totalTokens(),
                    elapsedMillis(requestStarted));
            return ToolCallResponse.answerWithReasoning(
                    answer.toString(), reasoningText, usage[0]);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("Chat tool stream was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelClientException(
                    "Chat tool stream request failed: " + exception.getMessage(), exception);
        }
    }

    /** 解析厂商 arguments JSON 文本为工具调用参数映射；空文本视为无参数。 */
    private Map<String, Object> parseToolCallArguments(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (!node.isObject()) {
                throw new ModelClientException(
                        "Tool call arguments for " + toolName + " were not a JSON object");
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            node.properties().forEach(entry ->
                    arguments.put(entry.getKey(), scalarValue(entry.getValue())));
            return arguments;
        } catch (JacksonException exception) {
            throw new ModelClientException(
                    "Tool call arguments for " + toolName + " were invalid JSON", exception);
        }
    }

    /** 标量取原生值，嵌套结构回退为 JSON 文本，与工具层参数绑定约定一致。 */
    private static Object scalarValue(JsonNode node) {
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private void consumeToolCallSseLine(
            String sessionId,
            String line,
            String model,
            ReasoningFilter filter,
            StringBuilder reasoning,
            ToolCallAccumulator toolCalls,
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
            LOGGER.debug("[chat-tools] skip non-JSON SSE line sessionId={}", sessionId);
            return;
        }
        if (usage[0] == null) {
            ModelUsage parsed = extractUsage(root, model);
            if (parsed != null) {
                usage[0] = parsed;
            }
        }
        JsonNode delta = root.path("choices").path(0).path("delta");
        JsonNode content = delta.path("content");
        if (content.isString() && !content.stringValue().isEmpty()) {
            filter.accept(content.stringValue());
        }
        JsonNode reasoningDelta = delta.path("reasoning_content");
        if (reasoningDelta.isString() && !reasoningDelta.stringValue().isEmpty()) {
            reasoning.append(reasoningDelta.stringValue());
        }
        JsonNode calls = delta.get("tool_calls");
        if (calls != null && calls.isArray()) {
            for (JsonNode call : calls) {
                toolCalls.accept(call);
            }
        }
    }

    /**
     * 聚合流式 {@code delta.tool_calls[]} 片段。
     *
     * <p>厂商按 index 分片下发：首个片段携带 {@code id} 与 {@code function.name}，
     * 后续片段只携带 {@code function.arguments} 增量。按 index 累积，流结束后
     * 输出完整调用。</p>
     */
    private static final class ToolCallAccumulator {

        private final Map<Integer, Chunk> chunks = new LinkedHashMap<>();

        record Completed(String id, String name, String argumentsJson) {
        }

        void accept(JsonNode call) {
            int index = call.path("index").asInt(0);
            Chunk chunk = chunks.computeIfAbsent(index, key -> new Chunk());
            String id = textValue(call.get("id"));
            if (id != null) {
                chunk.id = id;
            }
            JsonNode function = call.path("function");
            String name = textValue(function.get("name"));
            if (name != null) {
                chunk.name = chunk.name == null ? name : chunk.name + name;
            }
            JsonNode arguments = function.get("arguments");
            if (arguments != null && arguments.isString()) {
                chunk.arguments.append(arguments.stringValue());
            }
        }

        boolean isEmpty() {
            return chunks.isEmpty();
        }

        Completed firstCompleted() {
            return chunks.values().stream()
                    .filter(chunk -> chunk.name != null)
                    .findFirst()
                    .map(chunk -> new Completed(
                            chunk.id == null ? "call-0" : chunk.id,
                            chunk.name,
                            chunk.arguments.toString()))
                    .orElseThrow(() -> new ModelClientException(
                            "Tool call delta carried no function name"));
        }

        private static final class Chunk {
            private String id;
            private String name;
            private final StringBuilder arguments = new StringBuilder();
        }
    }

    private void consumeSseLine(
            String sessionId,
            String line,
            ReasoningFilter filter,
            StringBuilder reasoning,
            ModelUsage[] usage,
            String model) {
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
            ModelUsage parsed = extractUsage(root, model);
            if (parsed != null) {
                usage[0] = parsed;
            }
        }
        JsonNode delta = root.path("choices").path(0).path("delta");
        JsonNode content = delta.path("content");
        if (content.isString() && !content.stringValue().isEmpty()) {
            filter.accept(content.stringValue());
        }
        JsonNode reasoningDelta = delta.path("reasoning_content");
        if (reasoningDelta.isString() && !reasoningDelta.stringValue().isEmpty()) {
            reasoning.append(reasoningDelta.stringValue());
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
        body.put("model", modelFor(request));
        body.put("messages", requestMessages(request));
        if (stream) {
            body.put("stream", true);
            // 请求厂商在最后一个 SSE 块返回 usage；不支持的厂商会忽略该选项。
            body.put("stream_options", Map.of("include_usage", true));
        }
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream()
                    .map(OpenAiCompatibleChatClient::toolDefinition)
                    .collect(Collectors.toList()));
            body.put("tool_choice", "auto");
        }
        if (properties.isReasoningSplit()) {
            body.put("reasoning_split", true);
        }
        properties.applyGenerationOptions(body);
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

    /** 把 LlmRequest 展开为厂商消息数组：可选 system 指令 + user/assistant/tool 序列。 */
    private List<Map<String, Object>> requestMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        request.instruction().ifPresent(instruction -> messages.add(
                message("system", instruction)));
        for (LlmMessage message : request.messages()) {
            if (message.role() == LlmMessage.Role.TOOL) {
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", message.toolCallId());
                toolMessage.put("content", message.content());
                messages.add(toolMessage);
                continue;
            }
            if (message.role() == LlmMessage.Role.ASSISTANT
                    && (!message.toolCalls().isEmpty() || message.reasoningContent() != null)) {
                Map<String, Object> assistantMessage = new LinkedHashMap<>();
                assistantMessage.put("role", "assistant");
                if (message.reasoningContent() != null && reasoningPassthroughEnabled(request)) {
                    // 多轮 thinking 模式回传：模型要求历史 assistant 消息必须带回 reasoning_content。
                    assistantMessage.put("reasoning_content", message.reasoningContent());
                }
                if (message.toolCalls().isEmpty()) {
                    assistantMessage.put("content", message.content());
                } else {
                    assistantMessage.put("content", message.content());
                    assistantMessage.put("tool_calls", message.toolCalls().stream()
                            .map(call -> {
                                Map<String, Object> function = new LinkedHashMap<>();
                                function.put("name", call.name());
                                function.put("arguments", call.argumentsJson());
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("id", call.id());
                                payload.put("type", "function");
                                payload.put("function", function);
                                return payload;
                            })
                            .collect(Collectors.toList()));
                }
                messages.add(assistantMessage);
                continue;
            }
            messages.add(message(
                    message.role().name().toLowerCase(java.util.Locale.ROOT),
                    message.content()));
        }
        return messages;
    }

    /** 把统一工具定义映射为 OpenAI {@code tools[]} 元素。 */
    private static Map<String, Object> toolDefinition(LlmToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.name());
        function.put("description", definition.description());
        function.put("parameters", definition.parametersSchema() == null
                ? Map.of("type", "object", "properties", Map.of())
                : definition.parametersSchema());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "function");
        payload.put("function", function);
        return payload;
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

    private AnswerPayload extractAnswer(JsonNode root) {
        JsonNode message = root.path("choices").path(0).path("message");
        String refusal = textValue(message.get("refusal"));
        if (refusal != null) {
            throw new ModelClientException("Model refused to answer: " + refusal);
        }
        String reasoning = textValue(message.get("reasoning_content"));
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
                return new AnswerPayload(
                        requireVisibleAnswer(stripReasoning(text.toString())), reasoning);
            }
        }
        if (content.isString() && !content.stringValue().isBlank()) {
            return new AnswerPayload(
                    requireVisibleAnswer(stripReasoning(content.stringValue())), reasoning);
        }
        throw new ModelClientException("Chat response content was not usable text");
    }

    /** 解析后的回答载荷：可见正文与可选的推理内容。 */
    private record AnswerPayload(String answer, String reasoningContent) {
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

    private ModelUsage extractUsage(JsonNode root, String model) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        if (prompt <= 0 && completion <= 0) {
            return null;
        }
        return new ModelUsage(model, prompt, completion);
    }

    private String modelFor(LlmRequest request) {
        return request.model() == null || request.model().isBlank()
                ? properties.getEffectiveChatModel() : request.model();
    }

    /**
     * 判断当前目标模型是否回传历史 assistant 消息的 {@code reasoning_content}。
     *
     * <p>reasoning_content 是 thinking 模型（MiniMax-M3、DeepSeek-R1、Qwen3、GLM 等）
     * 的厂商扩展字段；发给不认识该字段的模型可能被严格网关拒绝。规则：</p>
     * <ol>
     * <li>{@code reasoningMode=DISABLED} 显式关闭；{@code ENABLED} 显式开启；</li>
     * <li>已知 thinking 厂商（MINIMAX/DEEPSEEK/QWEN/GLM）默认开启；</li>
     * <li>其他厂商按模型名启发式判断，避免会话中途切换模型时把字段发给非 thinking 模型。</li>
     * </ol>
     */
    private boolean reasoningPassthroughEnabled(LlmRequest request) {
        ModelClientProperties.ReasoningMode mode = properties.getReasoningMode();
        if (mode == ModelClientProperties.ReasoningMode.DISABLED) {
            return false;
        }
        if (mode == ModelClientProperties.ReasoningMode.ENABLED) {
            return true;
        }
        String provider = properties.getProviderType() == null
                ? "" : properties.getProviderType().trim();
        if ("MINIMAX".equalsIgnoreCase(provider) || "DEEPSEEK".equalsIgnoreCase(provider)
                || "QWEN".equalsIgnoreCase(provider) || "GLM".equalsIgnoreCase(provider)) {
            return true;
        }
        String model = modelFor(request).toLowerCase(java.util.Locale.ROOT);
        return THINKING_MODEL_HINTS.stream().anyMatch(model::contains);
    }

    /** 非 thinking 厂商下按模型名识别 thinking 模型的关键词。 */
    private static final List<String> THINKING_MODEL_HINTS = List.of(
            "minimax", "deepseek-r1", "qwq", "thinking", "reasoner");

    private String errorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = textValue(root.path("error").get("message"));
            if (message == null) {
                message = textValue(root.path("base_resp").get("status_msg"));
            }
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
