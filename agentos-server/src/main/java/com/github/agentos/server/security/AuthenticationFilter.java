package com.github.agentos.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;

/**
 * 统一鉴权过滤器：接受登录签发的 Bearer JWT 或机器调用的 X-API-Key。
 *
 * <p>JWT 校验通过后按其 claims 绑定 {@link RequestIdentity}（userId=用户名），
 * 后续 Memory ACL 等授权逻辑直接消费该身份；X-API-Key 调用保持服务端固定身份。
 * {@code /api/auth/login} 与 {@code /api/health} 为放行路径。两项凭据都未配置时
 * 过滤器整体关闭，保持本地开发零配置体验。</p>
 */
public final class AuthenticationFilter extends OncePerRequestFilter {

    /** 机器调用的备用凭据请求头。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> OPEN_PATHS = Set.of("/api/auth/login", "/api/health");

    private final JwtService jwtService;
    private final String apiKey;
    private final String identityTeamId;

    /**
     * 创建鉴权过滤器。
     *
     * @param jwtService 登录令牌校验服务；null 表示关闭登录凭据
     * @param apiKey     机器调用密钥；空表示关闭该凭据
     */
    public AuthenticationFilter(JwtService jwtService, String apiKey) {
        this(jwtService, apiKey, "default-team");
    }

    /** 指定 JWT 身份所属 team 的创建方式（测试与多 team 部署使用）。 */
    public AuthenticationFilter(JwtService jwtService, String apiKey, String identityTeamId) {
        this.jwtService = jwtService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.identityTeamId = identityTeamId == null || identityTeamId.isBlank()
                ? "default-team" : identityTeamId.trim();
    }

    /** 鉴权是否启用（任一凭据可用即启用）。 */
    public boolean enabled() {
        return jwtService != null || !apiKey.isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (OPEN_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.startsWith(BEARER_PREFIX)) {
                reject(response, "unsupported authorization scheme");
                return;
            }
            if (jwtService == null) {
                reject(response, "token authentication is disabled");
                return;
            }
            try {
                JwtService.AuthenticatedUser user =
                        jwtService.parse(authorization.substring(BEARER_PREFIX.length()).trim());
                request.setAttribute(RequestIdentity.REQUEST_ATTRIBUTE, new RequestIdentity(
                        identityTeamId, user.username(), user.roles()));
                chain.doFilter(request, response);
            } catch (JwtService.InvalidTokenException exception) {
                LOGGER.debug("[auth] rejected {} {}: {}",
                        request.getMethod(), path, exception.getMessage());
                reject(response, "invalid or expired token");
            }
            return;
        }

        String provided = request.getHeader(API_KEY_HEADER);
        if (provided != null && !provided.isBlank()) {
            if (apiKey.isEmpty() || !constantTimeEquals(apiKey, provided.trim())) {
                LOGGER.warn("[auth] rejected {} {}: invalid API key",
                        request.getMethod(), path);
                reject(response, "invalid API key");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        reject(response, "missing credentials");
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** 常量时间比对，避免时序侧信道泄露密钥内容。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AuthenticationFilter other
                && Objects.equals(apiKey, other.apiKey)
                && Objects.equals(identityTeamId, other.identityTeamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, identityTeamId);
    }
}
