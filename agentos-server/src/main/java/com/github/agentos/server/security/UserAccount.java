package com.github.agentos.server.security;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录用户账户。
 *
 * <p>{@code passwordHash} 为 {@link PasswordHasher} 生成的哈希串，绝不回传给客户端；
 * roles 为自由角色集合，内置语义：{@code ADMIN} 可管理用户，其余角色仅表示业务门禁。</p>
 */
public record UserAccount(String username, String passwordHash, Set<String> roles, Instant createdAt) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    /** 允许桌面壳访问本机工作区、Git 与交互终端。 */
    public static final String ROLE_WORKSPACE = "WORKSPACE";

    public UserAccount {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        username = username.trim();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roles = roles == null ? Set.of() : roles.stream()
                .filter(Objects::nonNull)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public boolean isAdmin() {
        return roles.contains(ROLE_ADMIN);
    }
}
