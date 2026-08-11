package com.github.agentos.server.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

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
    private ResponseFormat responseFormat = ResponseFormat.JSON_SCHEMA;
    private boolean reasoningSplit;
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
}
