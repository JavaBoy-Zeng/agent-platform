package com.github.agentos.server.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;

/** 由服务端安全过滤器绑定到请求的可信身份。 */
public record RequestIdentity(String teamId, String userId, Set<String> roles) {

    public static final String REQUEST_ATTRIBUTE = RequestIdentity.class.getName();
    public static final String MEMORY_ADMIN = "MEMORY_ADMIN";

    public RequestIdentity {
        if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        teamId = teamId.trim();
        userId = userId.trim();
        roles = roles == null ? Set.of() : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean memoryAdmin() {
        return roles.contains(MEMORY_ADMIN);
    }

    /**
     * 是否具有 ADMIN 角色：以用户库实时角色为准（与 /api/auth/me 口径一致），
     * 角色授予/回收立即生效，不依赖令牌 claims 的有效期；库中无此用户时回退令牌角色。
     */
    public boolean isAdmin(UserStore userStore) {
        return userStore.findByUsername(userId)
                .map(UserAccount::isAdmin)
                .orElse(roles.contains(UserAccount.ROLE_ADMIN));
    }

    public static RequestIdentity from(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof RequestIdentity identity) return identity;
        return new RequestIdentity("default-team", "default-user", Set.of());
    }
}
