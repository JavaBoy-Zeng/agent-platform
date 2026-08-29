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

/** 根据 PostgreSQL 中的用途路由为每次调用选择 Provider 和模型。 */
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
            ModelClientProperties properties = properties(
                    providers.resolve(ModelProviderService.ROUTE_PLANNER), defaults);
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
            return delegate().chat(sessionId, request);
        }

        @Override
        public ChatResponse chatDetails(String sessionId, LlmRequest request) {
            return delegate().chatDetails(sessionId, request);
        }

        @Override
        public ChatResponse chatStream(
                String sessionId, LlmRequest request, Consumer<String> onDelta) {
            return delegate().chatStream(sessionId, request, onDelta);
        }

        @Override
        public ToolCallResponse chatWithTools(
                String sessionId, LlmRequest request, Consumer<String> onDelta) {
            return delegate().chatWithTools(sessionId, request, onDelta);
        }

        private OpenAiCompatibleChatClient delegate() {
            ModelClientProperties properties = properties(
                    providers.resolve(ModelProviderService.ROUTE_CHAT), defaults);
            return new OpenAiCompatibleChatClient(
                    httpClient(properties), objectMapper, properties, usageListener);
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
        properties.setConnectTimeout(defaults.getConnectTimeout());
        properties.setRequestTimeout(defaults.getRequestTimeout());
        properties.setMaxPromptChars(defaults.getMaxPromptChars());
        properties.validate();
        return properties;
    }

    private static HttpClient httpClient(ModelClientProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
