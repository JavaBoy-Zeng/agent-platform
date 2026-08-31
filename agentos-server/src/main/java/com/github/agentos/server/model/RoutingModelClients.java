package com.github.agentos.server.model;

import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.ModelClient;
import com.github.agentos.planner.ModelPlan;
import com.github.agentos.planner.ModelUsageListener;
import com.github.agentos.planner.PlanningRequest;
import com.github.agentos.planner.flow.LlmRequest;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** 根据任务显式携带的平台模型 ID 为每次调用解析模型配置。 */
public final class RoutingModelClients {

    private RoutingModelClients() {
    }

    public static final class Planner implements ModelClient {
        private final ModelProviderService providers;
        private final ObjectMapper objectMapper;
        private final ModelUsageListener usageListener;
        private final ModelClientProperties defaults;
        private final Path allowedRoot;

        public Planner(
                ModelProviderService providers,
                ObjectMapper objectMapper,
                ModelUsageListener usageListener,
                ModelClientProperties defaults,
                Path allowedRoot) {
            this.providers = Objects.requireNonNull(providers);
            this.objectMapper = Objects.requireNonNull(objectMapper);
            this.usageListener = usageListener;
            this.defaults = Objects.requireNonNull(defaults);
            this.allowedRoot = allowedRoot;
        }

        @Override
        public ModelPlan generatePlan(PlanningRequest request) {
            Object modelId = request.agentRequest().attributes().get("modelId");
            ModelClientProperties properties = properties(
                    providers.resolve(text(modelId)),
                    defaults);
            return new OpenAiCompatibleModelClient(
                    httpClient(properties), objectMapper, properties, usageListener, allowedRoot)
                    .generatePlan(request);
        }
    }

    public static final class Chat implements ChatClient {
        private final ModelProviderService providers;
        private final ObjectMapper objectMapper;
        private final ModelUsageListener usageListener;
        private final ModelClientProperties defaults;

        public Chat(
                ModelProviderService providers,
                ObjectMapper objectMapper,
                ModelUsageListener usageListener,
                ModelClientProperties defaults) {
            this.providers = Objects.requireNonNull(providers);
            this.objectMapper = Objects.requireNonNull(objectMapper);
            this.usageListener = usageListener;
            this.defaults = Objects.requireNonNull(defaults);
        }

        @Override
        public String chat(String sessionId, LlmRequest request) {
            RoutedChat routed = route(request);
            return routed.client().chat(sessionId, routed.request());
        }

        @Override
        public ChatResponse chatDetails(String sessionId, LlmRequest request) {
            RoutedChat routed = route(request);
            return routed.client().chatDetails(sessionId, routed.request());
        }

        @Override
        public ChatResponse chatStream(
                String sessionId, LlmRequest request, Consumer<String> onDelta) {
            RoutedChat routed = route(request);
            return routed.client().chatStream(sessionId, routed.request(), onDelta);
        }

        @Override
        public ToolCallResponse chatWithTools(
                String sessionId, LlmRequest request, Consumer<String> onDelta) {
            RoutedChat routed = route(request);
            return routed.client().chatWithTools(sessionId, routed.request(), onDelta);
        }

        private RoutedChat route(LlmRequest request) {
            ModelProviderService.ResolvedModel resolved = providers.resolve(request.model());
            ModelClientProperties properties = properties(
                    resolved, defaults);
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(
                    httpClient(properties), objectMapper, properties, usageListener);
            return new RoutedChat(client, request.withModel(resolved.modelId()));
        }

        private record RoutedChat(OpenAiCompatibleChatClient client, LlmRequest request) {
        }
    }

    private static ModelClientProperties properties(
            ModelProviderService.ResolvedModel route, ModelClientProperties defaults) {
        ModelClientProperties properties = new ModelClientProperties();
        properties.setEndpoint(route.endpoint());
        properties.setApiKey(route.apiKey());
        properties.setModel(route.modelId());
        properties.setChatModel(route.modelId());
        properties.setResponseFormat(route.responseFormat());
        properties.setReasoningSplit(route.reasoningSplit());
        properties.setProviderType(route.providerType());
        ModelProviderService.AdvancedSettings advanced = route.advancedSettings();
        properties.setMaxOutputTokens(advanced.outputTokens());
        properties.setTemperature(advanced.temperature());
        properties.setTopP(advanced.topP());
        properties.setTopK(advanced.topK());
        properties.setReasoningMode(ModelClientProperties.ReasoningMode.valueOf(
                advanced.reasoningMode()));
        properties.setConnectTimeout(defaults.getConnectTimeout());
        properties.setRequestTimeout(defaults.getRequestTimeout());
        properties.setMaxPromptChars(promptCharacterLimit(
                advanced.inputTokens(), defaults.getMaxPromptChars()));
        properties.validate();
        return properties;
    }

    private static int promptCharacterLimit(Integer inputTokens, int fallback) {
        if (inputTokens == null) return fallback;
        // The planner currently bounds serialized prompts in characters. Two characters per token
        // is conservative for mixed Chinese/English prompts while still honoring larger windows.
        return (int) Math.clamp((long) inputTokens * 2L, 8_000L, 4_000_000L);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static HttpClient httpClient(ModelClientProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
