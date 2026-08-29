package com.github.agentos.server.model;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.agentos.server.persistence.mybatis.ModelProviderMapper;
import com.github.agentos.server.persistence.mybatis.ModelRouteMapper;
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

/** 模型服务商配置、运行时路由、密钥加密与连接测试的统一应用服务。 */
public class ModelProviderService {

    public static final String ROUTE_PLANNER = "planner";
    public static final String ROUTE_CHAT = "chat";

    private final boolean persistent;
    private final ModelProviderMapper providerMapper;
    private final ModelRouteMapper routeMapper;
    private final ObjectMapper objectMapper;
    private final ModelSecretCipher cipher;
    private final ModelClientProperties clientDefaults;
    private final Map<String, PersistenceRows.ModelProviderRow> memoryProviders =
            new ConcurrentHashMap<>();
    private final Map<String, PersistenceRows.ModelRouteRow> memoryRoutes =
            new ConcurrentHashMap<>();

    public ModelProviderService(
            String persistenceMode,
            ModelProviderMapper providerMapper,
            ModelRouteMapper routeMapper,
            ObjectMapper objectMapper,
            String secretKey,
            ModelClientProperties clientDefaults) {
        this.persistent = persistenceMode != null
                && ("postgresql".equalsIgnoreCase(persistenceMode.trim())
                || "postgres".equalsIgnoreCase(persistenceMode.trim()));
        this.providerMapper = providerMapper;
        this.routeMapper = routeMapper;
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clientDefaults = Objects.requireNonNull(clientDefaults);
        this.cipher = ModelSecretCipher.create(secretKey, persistent);
        bootstrapLegacyConfiguration();
    }

    public ManagementSnapshot snapshot() {
        List<ProviderView> providers = rows().stream().map(this::view).toList();
        List<RouteView> routes = routeRows().stream().map(this::routeView).toList();
        return new ManagementSnapshot(providers, routes);
    }

    public Optional<ProviderView> find(String providerId) {
        return Optional.ofNullable(providerRow(providerId)).map(this::view);
    }

    @Transactional
    public ProviderView create(SaveProviderRequest request) {
        ValidatedProvider value = validate(request, null);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PersistenceRows.ModelProviderRow row = new PersistenceRows.ModelProviderRow(
                id, value.displayName(), value.providerType(), value.protocol(),
                value.endpoint().toString(), cipher.encrypt(value.apiKey()),
                write(value.models()), value.defaultModel(), value.responseFormat(),
                value.reasoningSplit(), value.enabled(), "UNTESTED", "", null,
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
        String encryptedKey = value.apiKey().isEmpty()
                ? current.encryptedApiKey() : cipher.encrypt(value.apiKey());
        PersistenceRows.ModelProviderRow updated = new PersistenceRows.ModelProviderRow(
                current.providerId(), value.displayName(), value.providerType(), value.protocol(),
                value.endpoint().toString(), encryptedKey, write(value.models()),
                value.defaultModel(), value.responseFormat(), value.reasoningSplit(),
                value.enabled(), "UNTESTED", "", null, current.createdAt(), Instant.now(),
                current.version());
        updateProvider(updated);
        return view(requiredProvider(providerId));
    }

    @Transactional
    public void delete(String providerId) {
        PersistenceRows.ModelProviderRow row = requiredProvider(providerId);
        boolean inUse = routeRows().stream().anyMatch(route ->
                route.providerId().equals(row.providerId()));
        if (inUse) {
            throw new ProviderInUseException("请先把 planner/chat 路由切换到其他 Provider");
        }
        if (persistent) providerMapper.deleteById(row.providerId());
        else memoryProviders.remove(row.providerId());
    }

    @Transactional
    public RouteView assignRoute(String routeKey, AssignRouteRequest request) {
        String key = normalizeRoute(routeKey);
        PersistenceRows.ModelProviderRow provider = requiredProvider(request.providerId());
        if (!provider.enabled()) throw new IllegalArgumentException("不能路由到已停用的 Provider");
        List<String> models = readModels(provider.modelsPayload());
        String model = request.modelId() == null || request.modelId().isBlank()
                ? provider.defaultModel() : request.modelId().trim();
        if (!models.contains(model)) {
            throw new IllegalArgumentException("模型不在 Provider 的可选模型列表中: " + model);
        }
        PersistenceRows.ModelRouteRow current = routeRow(key);
        PersistenceRows.ModelRouteRow updated = new PersistenceRows.ModelRouteRow(
                key, provider.providerId(), model, Instant.now(),
                current == null ? 0 : current.version());
        if (persistent) {
            if (current == null) routeMapper.insert(updated);
            else routeMapper.updateById(updated);
        } else {
            memoryRoutes.put(key, updated);
        }
        return routeView(routeRow(key));
    }

    public ResolvedModel resolve(String routeKey) {
        String key = normalizeRoute(routeKey);
        PersistenceRows.ModelRouteRow route = routeRow(key);
        if (route == null) throw new IllegalStateException("模型路由尚未配置: " + key);
        PersistenceRows.ModelProviderRow provider = requiredProvider(route.providerId());
        if (!provider.enabled()) {
            throw new IllegalStateException("模型路由绑定的 Provider 已停用: " + provider.displayName());
        }
        return new ResolvedModel(
                route.routeKey(), provider.providerId(), provider.displayName(),
                URI.create(provider.endpoint()), cipher.decrypt(provider.encryptedApiKey()),
                route.modelId(), ModelClientProperties.ResponseFormat.valueOf(
                        provider.responseFormat()), provider.reasoningSplit(), provider.updatedAt());
    }

    /** 验证正式路由可解析且已保存的 API Key 能使用当前主密钥解密，不发起模型请求。 */
    public List<RouteValidation> validateRoutes() {
        return List.of(validateRoute(ROUTE_PLANNER), validateRoute(ROUTE_CHAT));
    }

    private RouteValidation validateRoute(String routeKey) {
        ResolvedModel model = resolve(routeKey);
        return new RouteValidation(
                model.routeKey(), model.providerId(), model.providerName(), model.modelId(),
                !model.apiKey().isBlank());
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
                provider.reasoningSplit(), provider.enabled(), status,
                "CONNECTED".equals(status) ? "" : message, Instant.now(), provider.createdAt(),
                Instant.now(), provider.version());
        updateProvider(checked);
        return new ConnectionTestResult(status, message, latencyMs, checked.lastCheckedAt());
    }

    private void bootstrapLegacyConfiguration() {
        if (!rows().isEmpty() || clientDefaults.getModel() == null
                || clientDefaults.getModel().isBlank()) return;
        String model = clientDefaults.getModel().trim();
        String chatModel = clientDefaults.getEffectiveChatModel().trim();
        List<String> models = model.equals(chatModel) ? List.of(model) : List.of(model, chatModel);
        ProviderView provider = create(new SaveProviderRequest(
                "Default OpenAI-compatible", "OPENAI_COMPATIBLE", "CHAT_COMPLETIONS",
                clientDefaults.getEndpoint().toString(), clientDefaults.getApiKey(), models,
                model, clientDefaults.getResponseFormat().name(),
                clientDefaults.isReasoningSplit(), true));
        assignRoute(ROUTE_PLANNER, new AssignRouteRequest(provider.id(), model));
        assignRoute(ROUTE_CHAT, new AssignRouteRequest(provider.id(), chatModel));
    }

    private ValidatedProvider validate(
            SaveProviderRequest request, PersistenceRows.ModelProviderRow current) {
        Objects.requireNonNull(request, "request must not be null");
        String displayName = requireText(request.displayName(), "displayName");
        String providerType = enumValue(request.providerType(), "providerType",
                List.of("OPENAI", "MINIMAX", "CC_SWITCH", "OPENAI_COMPATIBLE"));
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
        return new ValidatedProvider(displayName, providerType, protocol, endpoint, apiKey,
                models, defaultModel, responseFormat, request.reasoningSplit(), request.enabled());
    }

    private List<PersistenceRows.ModelProviderRow> rows() {
        return persistent
                ? providerMapper.selectList(new QueryWrapper<PersistenceRows.ModelProviderRow>()
                        .orderByAsc("created_at", "display_name"))
                : memoryProviders.values().stream()
                        .sorted(java.util.Comparator.comparing(PersistenceRows.ModelProviderRow::createdAt))
                        .toList();
    }

    private List<PersistenceRows.ModelRouteRow> routeRows() {
        return persistent
                ? routeMapper.selectList(new QueryWrapper<PersistenceRows.ModelRouteRow>()
                        .orderByAsc("route_key"))
                : memoryRoutes.values().stream()
                        .sorted(java.util.Comparator.comparing(PersistenceRows.ModelRouteRow::routeKey))
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

    private PersistenceRows.ModelRouteRow routeRow(String key) {
        return persistent ? routeMapper.selectById(key) : memoryRoutes.get(key);
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
                row.providerId(), row.displayName(), row.providerType(), row.protocol(),
                row.endpoint(), readModels(row.modelsPayload()), row.defaultModel(),
                row.responseFormat(), row.reasoningSplit(), row.enabled(),
                row.encryptedApiKey() != null && !row.encryptedApiKey().isBlank(),
                row.lastStatus(), row.lastError(), row.lastCheckedAt(),
                row.createdAt(), row.updatedAt());
    }

    private RouteView routeView(PersistenceRows.ModelRouteRow row) {
        PersistenceRows.ModelProviderRow provider = requiredProvider(row.providerId());
        return new RouteView(row.routeKey(), row.providerId(), provider.displayName(),
                row.modelId(), row.updatedAt());
    }

    private List<String> readModels(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<List<String>>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法读取 Provider 模型列表", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法序列化模型配置", exception);
        }
    }

    private static String normalizeRoute(String route) {
        String value = requireText(route, "routeKey").toLowerCase(Locale.ROOT);
        if (!ROUTE_PLANNER.equals(value) && !ROUTE_CHAT.equals(value)) {
            throw new IllegalArgumentException("routeKey 必须是 planner 或 chat");
        }
        return value;
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

    public record ManagementSnapshot(List<ProviderView> providers, List<RouteView> routes) {
    }

    public record ProviderView(
            String id, String displayName, String providerType, String protocol, String endpoint,
            List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, boolean enabled, boolean hasApiKey, String lastStatus,
            String lastError, Instant lastCheckedAt, Instant createdAt, Instant updatedAt) {
    }

    public record RouteView(
            String routeKey, String providerId, String providerName, String modelId,
            Instant updatedAt) {
    }

    public record RouteValidation(
            String routeKey, String providerId, String providerName, String modelId,
            boolean hasApiKey) {
    }

    public record SaveProviderRequest(
            String displayName, String providerType, String protocol, String endpoint,
            String apiKey, List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, boolean enabled) {
    }

    public record AssignRouteRequest(String providerId, String modelId) {
    }

    public record ConnectionTestResult(
            String status, String message, long latencyMs, Instant checkedAt) {
    }

    public record ResolvedModel(
            String routeKey, String providerId, String providerName, URI endpoint, String apiKey,
            String modelId, ModelClientProperties.ResponseFormat responseFormat,
            boolean reasoningSplit, Instant providerUpdatedAt) {
    }

    private record ValidatedProvider(
            String displayName, String providerType, String protocol, URI endpoint, String apiKey,
            List<String> models, String defaultModel, String responseFormat,
            boolean reasoningSplit, boolean enabled) {
    }

    public static final class ProviderNotFoundException extends RuntimeException {
        public ProviderNotFoundException(String message) { super(message); }
    }

    public static final class ProviderInUseException extends RuntimeException {
        public ProviderInUseException(String message) { super(message); }
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
