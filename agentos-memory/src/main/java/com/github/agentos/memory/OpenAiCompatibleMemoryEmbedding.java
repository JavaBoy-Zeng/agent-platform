package com.github.agentos.memory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 通过 OpenAI-compatible {@code /embeddings} 接口生成真实语义向量。 */
public final class OpenAiCompatibleMemoryEmbedding implements MemoryEmbedding {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    /**
     * 使用默认 JDK HTTP 客户端创建适配器。
     *
     * @param endpoint 完整的 OpenAI-compatible Embeddings HTTP(S) 地址
     * @param apiKey Bearer API Key；无需鉴权时可为空字符串
     * @param model Embedding 模型标识
     * @param timeout 单次请求超时
     */
    public OpenAiCompatibleMemoryEmbedding(
            URI endpoint, String apiKey, String model, Duration timeout) {
        this(defaultHttpClient(timeout), new ObjectMapper(),
                endpoint, apiKey, model, timeout);
    }

    /**
     * 使用可注入的 HTTP 和 JSON 组件创建适配器，便于连接池复用与契约测试。
     *
     * @param httpClient HTTP 客户端
     * @param objectMapper JSON 映射器
     * @param endpoint 完整的 Embeddings HTTP(S) 地址
     * @param apiKey Bearer API Key；无需鉴权时可为空字符串
     * @param model Embedding 模型标识
     * @param timeout 单次请求超时
     */
    public OpenAiCompatibleMemoryEmbedding(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        OpenAiCompatibleSupport.validateEndpoint(endpoint);
        OpenAiCompatibleSupport.validateModel(model);
        OpenAiCompatibleSupport.requirePositive(timeout, "timeout");
        this.endpoint = endpoint;
        this.apiKey = Objects.requireNonNullElse(apiKey, "");
        this.model = model.trim();
        this.timeout = timeout;
    }

    @Override
    public double[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("encoding_format", "float");
        JsonNode response = OpenAiCompatibleSupport.post(
                httpClient, objectMapper, endpoint, apiKey, timeout, body);
        JsonNode embedding = response.path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new MemoryAdapterException("embedding response does not contain data[0].embedding");
        }
        double[] vector = new double[embedding.size()];
        for (int index = 0; index < embedding.size(); index++) {
            JsonNode value = embedding.get(index);
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                throw new MemoryAdapterException("embedding response contains a non-finite value");
            }
            vector[index] = value.asDouble();
        }
        return vector;
    }

    private static HttpClient defaultHttpClient(Duration timeout) {
        OpenAiCompatibleSupport.requirePositive(timeout, "timeout");
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String modelId() {
        return model;
    }
}
