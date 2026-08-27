package com.github.agentos.server.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * PBKDF2 密码哈希，纯 JDK 实现。
 *
 * <p>存储格式：{@code pbkdf2:<iterations>:<saltBase64>:<hashBase64>}。
 * 算法参数升级时旧哈希仍可校验（按存储串中的迭代数重算）。</p>
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int DEFAULT_ITERATIONS = 120_000;
    private static final String PREFIX = "pbkdf2:";

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    /** 使用默认迭代数创建。 */
    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** 指定迭代数创建（测试用）。 */
    public PasswordHasher(int iterations) {
        if (iterations < 1_000) {
            throw new IllegalArgumentException("iterations must be at least 1000");
        }
        this.iterations = iterations;
    }

    /** 为明文密码生成可存储的哈希串。 */
    public String hash(String plainPassword) {
        Objects.requireNonNull(plainPassword, "plainPassword must not be null");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(plainPassword.toCharArray(), salt, iterations);
        return PREFIX + iterations
                + ":" + Base64.getEncoder().encodeToString(salt)
                + ":" + Base64.getEncoder().encodeToString(derived);
    }

    /** 校验明文密码是否匹配存储的哈希串；格式不合法一律返回 false。 */
    public boolean verify(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null || !storedHash.startsWith(PREFIX)) {
            return false;
        }
        try {
            String[] parts = storedHash.substring(PREFIX.length()).split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derive(plainPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM)
                    .generateSecret(spec)
                    .getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("pbkdf2 unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
