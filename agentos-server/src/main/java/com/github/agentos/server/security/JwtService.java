package com.github.agentos.server.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * 手写 HS256 JWT，纯 JDK 实现。
 *
 * <p>claims：{@code sub}（用户名）、{@code roles}（逗号分隔角色）、{@code iat}、{@code exp}、
 * {@code iss=agentos}。parse 阶段校验签名、签发者与过期时间。</p>
 */
public final class JwtService {

    private static final String ISSUER = "agentos";
    private static final String HEADER_B64 = base64Url(
            ("{\"alg\":\"HS256\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final Duration ttl;

    /** 使用显式密钥与有效期创建。 */
    public JwtService(byte[] secret, Duration ttl) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 32) {
            throw new IllegalArgumentException(
                    "auth secret must be at least 32 bytes for HS256");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("token ttl must be positive");
        }
        this.secret = secret;
        this.ttl = ttl;
    }

    /**
     * 签发访问令牌。
     *
     * @param username 用户名（sub）
     * @param roles    角色集合
     * @return token 串
     */
    public String issue(String username, Set<String> roles) {
        Objects.requireNonNull(username, "username must not be null");
        Instant now = Instant.now();
        String payload = "{\"sub\":\"" + escape(username.trim())
                + "\",\"roles\":\"" + escape(String.join(",", roles == null ? Set.of() : roles))
                + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plus(ttl).getEpochSecond()
                + ",\"iss\":\"" + ISSUER + "\"}";
        String payloadB64 = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER_B64 + "." + payloadB64;
        return signingInput + "." + base64Url(hmac(signingInput));
    }

    /**
     * 校验并解析 token。
     *
     * @param token JWT 串
     * @return 认证身份；签名/签发者/过期校验失败抛出 {@link InvalidTokenException}
     */
    public AuthenticatedUser parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token is blank");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !HEADER_B64.equals(parts[0])) {
            throw new InvalidTokenException("malformed token");
        }
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTokenException("bad signature encoding");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new InvalidTokenException("signature mismatch");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String subject = stringClaim(payload, "sub");
        String issuer = stringClaim(payload, "iss");
        long expiration = longClaim(payload, "exp");
        if (!ISSUER.equals(issuer)) {
            throw new InvalidTokenException("unexpected issuer");
        }
        if (Instant.ofEpochSecond(expiration).isBefore(Instant.now())) {
            throw new InvalidTokenException("token expired");
        }
        String rolesField = stringClaim(payload, "roles");
        Set<String> roles = rolesField == null || rolesField.isBlank()
                ? Set.of()
                : Set.of(rolesField.split(","));
        return new AuthenticatedUser(subject, roles,
                Instant.ofEpochSecond(expiration));
    }

    /** token 有效期。 */
    public Duration ttl() {
        return ttl;
    }

    /** 生成一次性随机签名密钥（未配置 secret 时使用；重启后所有 token 失效）。 */
    public static byte[] randomSecret() {
        byte[] secret = new byte[48];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("hmac-sha256 unavailable", exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringClaim(String payload, String key) {
        int keyIndex = payload.indexOf("\"" + key + "\":");
        if (keyIndex < 0) {
            return null;
        }
        int valueStart = payload.indexOf('"', keyIndex + key.length() + 2);
        int valueEnd = payload.indexOf('"', valueStart + 1);
        if (valueStart < 0 || valueEnd < 0) {
            return null;
        }
        return payload.substring(valueStart + 1, valueEnd).replace("\\\"", "\"");
    }

    private static long longClaim(String payload, String key) {
        int keyIndex = payload.indexOf("\"" + key + "\":");
        if (keyIndex < 0) {
            throw new InvalidTokenException("missing claim " + key);
        }
        int start = keyIndex + key.length() + 3;
        int end = start;
        while (end < payload.length() && (Character.isDigit(payload.charAt(end)))) {
            end++;
        }
        return Long.parseLong(payload.substring(start, end));
    }

    /** 解析后的认证身份。 */
    public record AuthenticatedUser(String username, Set<String> roles, Instant expiresAt) {
    }

    /** token 无效（格式、签名、签发者、过期）。 */
    public static final class InvalidTokenException extends RuntimeException {
        /** 创建异常。 */
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
