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

/**
 * 基于 API Key 的请求鉴权过滤器。
 *
 * <p>从 {@code X-API-Key} 请求头读取调用方密钥，与服务端配置的
 * {@code agentos.security.api-key} 常量时间比对；不匹配时返回 401。
 * 配置为空时过滤器直接放行，保持本地开发零配置体验。</p>
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 调用方携带密钥的请求头名称。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final String apiKey;

    /** 创建鉴权过滤器；空密钥表示关闭鉴权。 */
    public ApiKeyAuthFilter(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** 鉴权是否启用。 */
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || !constantTimeEquals(apiKey, provided.trim())) {
            LOGGER.warn("[auth] rejected {} {}: missing or invalid API key",
                    request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"missing or invalid API key\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 常量时间比对，避免时序侧信道泄露密钥内容。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApiKeyAuthFilter other && Objects.equals(other.apiKey, apiKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey);
    }
}
