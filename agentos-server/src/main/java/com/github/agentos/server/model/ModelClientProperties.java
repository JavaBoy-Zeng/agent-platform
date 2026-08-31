package com.github.agentos.server.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI-compatible model endpoint settings.
 *
 * <p>Spring Boot can bind these values from {@code agentos.model.*} properties or the matching
 * {@code AGENTOS_MODEL_*} environment variables.</p>
 */
@ConfigurationProperties("agentos.model")
public class ModelClientProperties {

    private URI endpoint = URI.create("https://api.openai.com/v1/chat/completions");
    private String apiKey = "";
    private String model = "";
    private String chatModel = "";
    private ResponseFormat responseFormat = ResponseFormat.JSON_SCHEMA;
    private boolean reasoningSplit;
    private String providerType = "OPENAI_COMPATIBLE";
    private Integer maxOutputTokens;
    private Double temperature;
    private Double topP;
    private Integer topK;
    private ReasoningMode reasoningMode = ReasoningMode.DEFAULT;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(60);
    private int maxPromptChars = 60_000;

    /** Returns the complete Chat Completions endpoint. */
    public URI getEndpoint() {
        return endpoint;
    }

    /** Sets the complete Chat Completions endpoint. */
    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    /** Returns the optional bearer token. */
    public String getApiKey() {
        return apiKey;
    }

    /** Sets the optional bearer token. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Returns the provider model identifier. */
    public String getModel() {
        return model;
    }

    /** Sets the provider model identifier. */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * Returns the provider model identifier used by the lightweight direct-chat path,
     * falling back to the main model when unset.
     */
    public String getEffectiveChatModel() {
        return chatModel == null || chatModel.isBlank() ? model : chatModel;
    }

    /** Returns the configured direct-chat model identifier (may be blank). */
    public String getChatModel() {
        return chatModel;
    }

    /** Sets the optional lighter/faster model used for direct chat answers. */
    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    /** Returns the requested structured-output mode. */
    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    /** Sets the requested structured-output mode. */
    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
    }

    /** Returns whether provider reasoning should be separated from final message content. */
    public boolean isReasoningSplit() {
        return reasoningSplit;
    }

    /** Sets whether to request a separate provider reasoning field. */
    public void setReasoningSplit(boolean reasoningSplit) {
        this.reasoningSplit = reasoningSplit;
    }

    /** Returns the configured provider family used for compatible request extensions. */
    public String getProviderType() {
        return providerType;
    }

    /** Sets the provider family used for compatible request extensions. */
    public void setProviderType(String providerType) {
        this.providerType = providerType == null ? "OPENAI_COMPATIBLE" : providerType;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public ReasoningMode getReasoningMode() {
        return reasoningMode;
    }

    public void setReasoningMode(ReasoningMode reasoningMode) {
        this.reasoningMode = reasoningMode == null ? ReasoningMode.DEFAULT : reasoningMode;
    }

    /** Adds optional OpenAI-compatible generation controls without overriding provider defaults. */
    public void applyGenerationOptions(Map<String, Object> body) {
        if (maxOutputTokens != null) body.put("max_tokens", maxOutputTokens);
        if (temperature != null) body.put("temperature", temperature);
        if (topP != null) body.put("top_p", topP);
        if (topK != null) body.put("top_k", topK);
        if (reasoningMode == ReasoningMode.DEFAULT) return;
        boolean enabled = reasoningMode == ReasoningMode.ENABLED;
        if ("QWEN".equalsIgnoreCase(providerType)) {
            body.put("enable_thinking", enabled);
        } else if ("DEEPSEEK".equalsIgnoreCase(providerType)
                || "GLM".equalsIgnoreCase(providerType)) {
            body.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
        }
    }

    /** Returns the HTTP connection timeout. */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /** Sets the HTTP connection timeout. */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** Returns the complete request timeout. */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /** Sets the complete request timeout. */
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /** Returns the maximum character count of one user planning prompt. */
    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    /** Sets the maximum character count of one user planning prompt. */
    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    /**
     * Checks settings before the HTTP client is exposed to the planner.
     */
    public void validate() {
        if (endpoint == null || endpoint.getScheme() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalStateException("agentos.model.endpoint must be an HTTP(S) URI");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "agentos.model.model must not be blank; configure application.yml or AGENTOS_MODEL_MODEL");
        }
        if (responseFormat == null) {
            throw new IllegalStateException("agentos.model.response-format must not be null");
        }
        requirePositive(connectTimeout, "agentos.model.connect-timeout");
        requirePositive(requestTimeout, "agentos.model.request-timeout");
        if (maxPromptChars < 8_000) {
            throw new IllegalStateException("agentos.model.max-prompt-chars must be at least 8000");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalStateException("agentos.model.max-output-tokens must be positive");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalStateException("agentos.model.temperature must be between 0 and 2");
        }
        if (topP != null && (topP < 0 || topP > 1)) {
            throw new IllegalStateException("agentos.model.top-p must be between 0 and 1");
        }
        if (topK != null && (topK < 1 || topK > 100)) {
            throw new IllegalStateException("agentos.model.top-k must be between 1 and 100");
        }
    }

    private static void requirePositive(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(propertyName + " must be positive");
        }
    }

    /** Structured-output variants commonly implemented by OpenAI-compatible providers. */
    public enum ResponseFormat {
        /** Send a dynamic JSON Schema describing the plan and registered tools. */
        JSON_SCHEMA,
        /** Request a JSON object without schema enforcement. */
        JSON_OBJECT,
        /** Rely only on the prompt and omit {@code response_format}. */
        NONE
    }

    /** Whether a provider should use its default, thinking, or non-thinking mode. */
    public enum ReasoningMode {
        DEFAULT,
        ENABLED,
        DISABLED
    }
}
