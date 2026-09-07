package com.github.agentos.server.model;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.agentos.server.persistence.mybatis.ModelProviderMapper;
import com.github.agentos.server.persistence.mybatis.PersistenceRows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 模型配置、密钥加密、运行时解析与连接测试的统一应用服务。 */
public class ModelProviderService {

    public static final String MODEL_TYPE_BUILT_IN = "BUILT_IN";
    public static final String MODEL_TYPE_CUSTOM = "CUSTOM";

    private final boolean persistent;
    private final ModelProviderMapper providerMapper;
    private final ObjectMapper objectMapper;
    private final ModelSecretCipher cipher;
    private final Map<String, PersistenceRows.ModelProviderRow> memoryProviders =
            new ConcurrentHashMap<>();

    public ModelProviderService(
            String persistenceMode,
            ModelProviderMapper providerMapper,
            ObjectMapper objectMapper,
            String secretKey) {
        this.persistent = persistenceMode != null
                && ("postgresql".equalsIgnoreCase(persistenceMode.trim())
                || "postgres".equalsIgnoreCase(persistenceMode.trim()));
        this.providerMapper = providerMapper;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.cipher = ModelSecretCipher.create(secretKey, persistent);
    }

    public ManagementSnapshot snapshot() {
        List<ProviderView> providers = rows().stream().map(this::view).toList();
        return new ManagementSnapshot(providers, modelOptions());
    }

    public Optional<ProviderView> find(String providerId) {
        return Optional.ofNullable(providerRow(providerId)).map(this::view);
    }

    @Transactional
    public ProviderView create(SaveProviderRequest request) {
        ValidatedProvider value = validate(request, null);
        ensureUniqueModels(value, null);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PersistenceRows.ModelProviderRow row = new PersistenceRows.ModelProviderRow(
                id, value.displayName(), value.providerType(), value.protocol(),
                value.endpoint().toString(), cipher.encrypt(value.apiKey()),
                write(value.models()), value.defaultModel(), value.responseFormat(),
                value.reasoningSplit(), write(value.advancedSettings()), value.enabled(),
                "UNTESTED", "", null,
                now, now, 0);
        try {
            insertProvider(row);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("模型服务名称已存在: " + value.displayName(), exception);
        }
        return view(row);
    }

    @Transactional
    public ProviderView update(String providerId, SaveProviderRequest request) {
        PersistenceRows.ModelProviderRow current = requiredProvider(providerId);
        ValidatedProvider value = validate(request, current);
        ensureUniqueModels(value, current);
        String encryptedKey = value.apiKey().isEmpty()
                ? current.encryptedApiKey() : cipher.encrypt(value.apiKey());
        PersistenceRows.ModelProviderRow updated = new PersistenceRows.ModelProviderRow(
                current.providerId(), value.displayName(), value.providerType(), value.protocol(),
                value.endpoint().toString(), encryptedKey, write(value.models()),
                value.defaultModel(), value.responseFormat(), value.reasoningSplit(),
                write(value.advancedSettings()), value.enabled(), "UNTESTED", "", null,
                current.createdAt(), Instant.now(), current.version());
        updateProvider(updated);
        return view(requiredProvider(providerId));
    }

    @Transactional
    public void delete(String providerId) {
        PersistenceRows.ModelProviderRow row = requiredProvider(providerId);
        if (persistent) providerMapper.deleteById(row.providerId());
        else memoryProviders.remove(row.providerId());
    }

    /** 根据任务携带的平台模型 ID 解析完整配置；不提供任何全局或隐式回退。 */
    public ResolvedModel resolve(String modelId) {
        String selectedId = requireText(modelId, "modelId");
        for (PersistenceRows.ModelProviderRow provider : rows()) {
            for (String vendorModelId : readModels(provider.modelsPayload())) {
                if (configuredModelId(provider.providerId(), vendorModelId).equals(selectedId)) {
                    return resolveProvider(selectedId, provider, vendorModelId);
                }
            }
        }
        throw new ModelNotFoundException("模型不存在或配置已变更: " + selectedId);
    }

    private ResolvedModel resolveProvider(
            String configuredModelId, PersistenceRows.ModelProviderRow provider,
            String vendorModelId) {
        if (!provider.enabled()) {
            throw new IllegalStateException("所选模型已停用: " + vendorModelId);
        }
        return new ResolvedModel(
                configuredModelId, provider.providerId(), provider.displayName(),
                URI.create(provider.endpoint()), cipher.decrypt(provider.encryptedApiKey()),
                vendorModelId, ModelClientProperties.ResponseFormat.valueOf(
                        provider.responseFormat()), provider.providerType(),
                provider.reasoningSplit(), readSettings(provider.settingsPayload()),
                provider.updatedAt());
    }

    /** 验证全部已启用模型可使用当前主密钥解密，不要求系统必须配置模型。 */
    public List<ModelValidation> validateModels() {
        List<ModelValidation> validations = new ArrayList<>();
        for (ModelOptionView model : modelOptions()) {
            if (!model.enabled()) continue;
            ResolvedModel resolved = resolve(model.id());
            validations.add(new ModelValidation(
                    model.id(), model.modelId(), model.modelType(), model.providerName(),
                    !resolved.apiKey().isBlank()));
        }
        return List.copyOf(validations);
    }

    @Transactional
    public ConnectionTestResult test(String providerId) {
        PersistenceRows.ModelProviderRow provider = requiredProvider(providerId);
        long started = System.nanoTime();
        String status = "CONNECTED";
        String message = "连接成功";
        try {
            String apiKey = cipher.decrypt(provider.encryptedApiKey());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", provider.defaultModel());
            payload.put("messages", List.of(Map.of(
                    "role", "user", "content", "Reply with OK.")));
            payload.put("max_tokens", 8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.endpoint()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(write(payload)));
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                        + sanitize(response.body(), apiKey));
            }
        } catch (Exception exception) {
            status = "FAILED";
            message = sanitize(exception.getMessage(), cipher.decrypt(provider.encryptedApiKey()));
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        PersistenceRows.ModelProviderRow checked = new PersistenceRows.ModelProviderRow(
                provider.providerId(), provider.displayName(), provider.providerType(),
                provider.protocol(), provider.endpoint(), provider.encryptedApiKey(),
                provider.modelsPayload(), provider.defaultModel(), provider.responseFormat(),
                provider.reasoningSplit(), provider.settingsPayload(), provider.enabled(), status,
                "CONNECTED".equals(status) ? "" : message, Instant.now(), provider.createdAt(),
                Instant.now(), provider.version());
        updateProvider(checked);
        return new ConnectionTestResult(status, message, latencyMs, checked.lastCheckedAt());
    }

    private ValidatedProvider validate(
            SaveProviderRequest request, PersistenceRows.ModelProviderRow current) {
        Objects.requireNonNull(request, "request must not be null");
        String displayName = requireText(request.displayName(), "displayName");
        String providerType = enumValue(request.providerType(), "providerType",
                List.of("OPENAI", "DEEPSEEK", "GLM", "QWEN", "MINIMAX",
                        "CC_SWITCH", "OPENAI_COMPATIBLE"));
        String protocol = enumValue(request.protocol(), "protocol", List.of("CHAT_COMPLETIONS"));
        URI endpoint;
        try {
            endpoint = URI.create(requireText(request.endpoint(), "endpoint"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("endpoint 必须是有效的 HTTP(S) 地址", exception);
        }
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint 必须使用 HTTP 或 HTTPS");
        }
        List<String> models = request.models() == null ? new ArrayList<>()
                : request.models().stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        String defaultModel = requireText(request.defaultModel(), "defaultModel");
        if (!models.contains(defaultModel)) {
            List<String> withDefault = new ArrayList<>(models);
            withDefault.add(0, defaultModel);
            models = withDefault.stream().distinct().toList();
        }
        String responseFormat = enumValue(request.responseFormat(), "responseFormat",
                List.of("JSON_SCHEMA", "JSON_OBJECT", "NONE"));
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        AdvancedSettings advancedSettings = validateSettings(request.advancedSettings());
        return new ValidatedProvider(displayName, providerType, protocol, endpoint, apiKey,
                models, defaultModel, responseFormat, request.reasoningSplit(),
                advancedSettings, request.enabled());
    }

    private void ensureUniqueModels(
            ValidatedProvider candidate, PersistenceRows.ModelProviderRow current) {
        String candidateType = modelType(candidate.providerType());
        for (PersistenceRows.ModelProviderRow existing : rows()) {
            if (current != null && existing.providerId().equals(current.providerId())) continue;
            if (!modelType(existing.providerType()).equals(candidateType)) continue;
            for (String modelId : candidate.models()) {
                if (readModels(existing.modelsPayload()).contains(modelId)) {
                    throw new DuplicateModelException(
                            "模型已存在: " + modelId + "（" + modelTypeLabel(candidateType) + "）");
                }
            }
        }
    }

    /** Provider 表是否为空（用于启动引导：空时按 agentos.model.* 自动 seed）。 */
    public boolean isEmpty() {
        return rows().isEmpty();
    }

    /**
     * 在 Provider 表为空时按 {@code agentos.model.*} 兜底配置自动 seed 一条
     * {@code OPENAI_COMPATIBLE} 记录，使普通用户也能开箱即用。
     *
     * <p>已有任何 Provider 时直接跳过，避免覆盖运维手工配置；密钥为空时仍可创建，
     * 仅在做实际模型调用时表现为未鉴权，由运维后续补充。</p>
     */
    @Transactional
    public Optional<ProviderView> seedFromProperties(ModelClientProperties defaults) {
        if (!rows().isEmpty() || defaults == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(defaults.getEndpoint(), "agentos.model.endpoint must not be null");
        String model = defaults.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "agentos.model.model must not be blank; configure it before starting");
        }
        SaveProviderRequest seed = new SaveProviderRequest(
                "Default (agentos.model)",
                "OPENAI_COMPATIBLE",
                "CHAT_COMPLETIONS",
                defaults.getEndpoint().toString(),
                defaults.getApiKey() == null ? "" : defaults.getApiKey(),
                List.of(model),
                model,
                defaults.getResponseFormat() == null
                        ? "JSON_OBJECT" : defaults.getResponseFormat().name(),
                defaults.isReasoningSplit(),
                null,
                true);
        return Optional.of(create(seed));
    }

    private List<PersistenceRows.ModelProviderRow> rows() {
        return persistent
                ? providerMapper.selectList(new QueryWrapper<PersistenceRows.ModelProviderRow>()
                        .orderByAsc("created_at", "display_name"))
                : memoryProviders.values().stream()
                        .sorted(java.util.Comparator.comparing(PersistenceRows.ModelProviderRow::createdAt))
                        .toList();
    }

    private PersistenceRows.ModelProviderRow providerRow(String id) {
        String value = requireText(id, "providerId");
        return persistent ? providerMapper.selectById(value) : memoryProviders.get(value);
    }

    private PersistenceRows.ModelProviderRow requiredProvider(String id) {
        PersistenceRows.ModelProviderRow row = providerRow(id);
        if (row == null) throw new ProviderNotFoundException("模型 Provider 不存在: " + id);
        return row;
    }

    private void insertProvider(PersistenceRows.ModelProviderRow row) {
        if (persistent) providerMapper.insert(row);
        else memoryProviders.put(row.providerId(), row);
    }

    private void updateProvider(PersistenceRows.ModelProviderRow row) {
        if (persistent) providerMapper.updateById(row);
        else memoryProviders.put(row.providerId(), row);
    }

    private ProviderView view(PersistenceRows.ModelProviderRow row) {
        return new ProviderView(
                row.providerId(), row.displayName(), row.providerType(),
                modelType(row.providerType()), row.protocol(),
                row.endpoint(), readModels(row.modelsPayload()), row.defaultModel(),
                row.responseFormat(), row.reasoningSplit(), readSettings(row.settingsPayload()),
                row.enabled(),
                row.encryptedApiKey() != null && !row.encryptedApiKey().isBlank(),
                row.lastStatus(), row.lastError(), row.lastCheckedAt(),
                row.createdAt(), row.updatedAt());
    }

    private List<ModelOptionView> modelOptions() {
        List<ModelOptionView> models = new ArrayList<>();
        for (PersistenceRows.ModelProviderRow provider : rows()) {
            String type = modelType(provider.providerType());
            for (String vendorModelId : readModels(provider.modelsPayload())) {
                models.add(new ModelOptionView(
                        configuredModelId(provider.providerId(), vendorModelId),
                        vendorModelId, type, provider.providerId(), provider.displayName(),
                        provider.providerType(), provider.enabled()));
            }
        }
        return List.copyOf(models);
    }

    private List<String> readModels(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<List<String>>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法读取 Provider 模型列表", exception);
        }
    }

    private AdvancedSettings readSettings(String payload) {
        if (payload == null || payload.isBlank()) return AdvancedSettings.defaults();
        try {
            return validateSettings(objectMapper.readValue(payload, AdvancedSettings.class));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("无法读取 Provider 高级配置", exception);
        }
    }

    private static AdvancedSettings validateSettings(AdvancedSettings settings) {
        AdvancedSettings value = settings == null ? AdvancedSettings.defaults() : settings;
        Integer inputTokens = optionalRange(value.inputTokens(), 1, 2_000_000, "inputTokens");
        Integer outputTokens = optionalRange(value.outputTokens(), 1, 512_000, "outputTokens");
        Integer toolCallRounds = optionalRange(
                value.toolCallRounds() == null ? 500 : value.toolCallRounds(),
                1, 500, "toolCallRounds");
        Double temperature = optionalRange(value.temperature(), 0, 2, "temperature");
        Double topP = optionalRange(value.topP(), 0, 1, "topP");
        Integer topK = optionalRange(value.topK(), 1, 100, "topK");
        String reasoningMode = value.reasoningMode() == null || value.reasoningMode().isBlank()
                ? ModelClientProperties.ReasoningMode.DEFAULT.name()
                : enumValue(value.reasoningMode(), "reasoningMode",
                        List.of("DEFAULT", "ENABLED", "DISABLED"));
        return new AdvancedSettings(inputTokens, outputTokens, toolCallRounds,
                Boolean.TRUE.equals(value.imageInput()), reasoningMode,
                temperature, topP, topK);
    }

    private static Integer optionalRange(Integer value, int minimum, int maximum, String field) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    private static Double optionalRange(Double value, double minimum, double maximum, String field) {
        if (value != null && (!Double.isFinite(value) || value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法序列化模型配置", exception);
        }
    }

    private static String configuredModelId(String providerId, String vendorModelId) {
        byte[] source = (providerId + "\u0000" + vendorModelId).getBytes(StandardCharsets.UTF_8);
        return "model-" + UUID.nameUUIDFromBytes(source);
    }

    private static String modelType(String providerType) {
        return "OPENAI_COMPATIBLE".equals(providerType)
                ? MODEL_TYPE_CUSTOM : MODEL_TYPE_BUILT_IN;
    }

    private static String modelTypeLabel(String modelType) {
        return MODEL_TYPE_CUSTOM.equals(modelType) ? "自定义" : "内置";
    }

    private static String enumValue(String value, String field, List<String> allowed) {
        String normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " 必须是: " + String.join(", ", allowed));
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value.trim();
    }

    private static String sanitize(String message, String apiKey) {
        String value = message == null || message.isBlank() ? "未知连接错误" : message;
        if (apiKey != null && !apiKey.isBlank()) value = value.replace(apiKey, "***");
        value = value.replaceAll("(?i)bearer\\s+[a-z0-9._~+/-]+", "Bearer ***");
        return value.length() > 400 ? value.substring(0, 400) + "…" : value;
    }

    public record ManagementSnapshot(
            List<ProviderView> providers, List<ModelOptionView> models) {
    }

    public record ProviderView(
            String id, String displayName, String providerType, String modelType,
            String protocol, String endpoint,
            List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, AdvancedSettings advancedSettings, boolean enabled,
            boolean hasApiKey, String lastStatus,
            String lastError, Instant lastCheckedAt, Instant createdAt, Instant updatedAt) {
    }

    public record ModelOptionView(
            String id, String modelId, String modelType, String providerId,
            String providerName, String providerType, boolean enabled) {
    }

    public record ModelValidation(
            String id, String modelId, String modelType, String providerName,
            boolean hasApiKey) {
    }

    public record SaveProviderRequest(
            String displayName, String providerType, String protocol, String endpoint,
            String apiKey, List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, AdvancedSettings advancedSettings, boolean enabled) {
    }

    public record ConnectionTestResult(
            String status, String message, long latencyMs, Instant checkedAt) {
    }

    public record ResolvedModel(
            String id, String providerId, String providerName, URI endpoint, String apiKey,
            String modelId, ModelClientProperties.ResponseFormat responseFormat,
            String providerType, boolean reasoningSplit, AdvancedSettings advancedSettings,
            Instant providerUpdatedAt) {
    }

    /** Optional model capabilities and request sampling controls; null values keep provider defaults. */
    public record AdvancedSettings(
            Integer inputTokens, Integer outputTokens, Integer toolCallRounds,
            Boolean imageInput, String reasoningMode, Double temperature, Double topP,
            Integer topK) {

        public static AdvancedSettings defaults() {
            return new AdvancedSettings(
                    null, null, 500, false, "DEFAULT", null, null, null);
        }
    }

    private record ValidatedProvider(
            String displayName, String providerType, String protocol, URI endpoint, String apiKey,
            List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, AdvancedSettings advancedSettings, boolean enabled) {
    }

    public static final class ProviderNotFoundException extends RuntimeException {
        public ProviderNotFoundException(String message) { super(message); }
    }

    public static final class ModelNotFoundException extends RuntimeException {
        public ModelNotFoundException(String message) { super(message); }
    }

    public static final class DuplicateModelException extends RuntimeException {
        public DuplicateModelException(String message) { super(message); }
    }

    /** AES-256-GCM 密钥封装；主密钥只从环境配置读取，不进入数据库。 */
    private static final class ModelSecretCipher {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final int IV_BYTES = 12;
        private final SecretKeySpec key;

        private ModelSecretCipher(byte[] key) {
            this.key = new SecretKeySpec(key, "AES");
        }

        static ModelSecretCipher create(String configured, boolean persistent) {
            if (configured == null || configured.isBlank()) {
                if (persistent) {
                    throw new IllegalStateException(
                            "AGENTOS_MODEL_SECRET_KEY must contain a Base64-encoded 32-byte key");
                }
                byte[] ephemeral = new byte[32];
                RANDOM.nextBytes(ephemeral);
                return new ModelSecretCipher(ephemeral);
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("AGENTOS_MODEL_SECRET_KEY must be valid Base64", exception);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException("AGENTOS_MODEL_SECRET_KEY must decode to exactly 32 bytes");
            }
            return new ModelSecretCipher(decoded);
        }

        String encrypt(String plaintext) {
            if (plaintext == null || plaintext.isBlank()) return "";
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            try {
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] encrypted = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                byte[] combined = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
                return "v1:" + Base64.getEncoder().encodeToString(combined);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("无法加密模型 API Key", exception);
            }
        }

        String decrypt(String encoded) {
            if (encoded == null || encoded.isBlank()) return "";
            if (!encoded.startsWith("v1:")) throw new IllegalStateException("未知的模型密钥格式");
            byte[] combined = Base64.getDecoder().decode(encoded.substring(3));
            if (combined.length <= IV_BYTES) throw new IllegalStateException("模型密钥密文已损坏");
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            try {
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                return new String(aes.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("无法解密模型 API Key，请检查主密钥", exception);
            }
        }
    }
}
