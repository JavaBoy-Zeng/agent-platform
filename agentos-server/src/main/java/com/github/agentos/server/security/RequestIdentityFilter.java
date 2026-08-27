package com.github.agentos.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将 API 请求绑定到服务端配置身份；只有显式信任上游身份头时才读取调用方 Header。
 */
public final class RequestIdentityFilter extends OncePerRequestFilter {

    public static final String TEAM_HEADER = "X-AgentOS-Team-Id";
    public static final String USER_HEADER = "X-AgentOS-User-Id";
    public static final String ROLES_HEADER = "X-AgentOS-Roles";

    private final RequestIdentity fixedIdentity;
    private final boolean trustHeaders;

    public RequestIdentityFilter(
            String teamId, String userId, String roles, boolean trustHeaders) {
        this.fixedIdentity = new RequestIdentity(teamId, userId, parseRoles(roles));
        this.trustHeaders = trustHeaders;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 登录鉴权已绑定真实用户身份时不再覆盖，保持服务端固定身份仅用于匿名凭据（X-API-Key）。
        if (request.getAttribute(RequestIdentity.REQUEST_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }
        RequestIdentity identity = fixedIdentity;
        if (trustHeaders) {
            String teamId = textOr(request.getHeader(TEAM_HEADER), fixedIdentity.teamId());
            String userId = textOr(request.getHeader(USER_HEADER), fixedIdentity.userId());
            Set<String> roles = request.getHeader(ROLES_HEADER) == null
                    ? fixedIdentity.roles()
                    : parseRoles(request.getHeader(ROLES_HEADER));
            identity = new RequestIdentity(teamId, userId, roles);
        }
        request.setAttribute(RequestIdentity.REQUEST_ATTRIBUTE, identity);
        chain.doFilter(request, response);
    }

    private static Set<String> parseRoles(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
