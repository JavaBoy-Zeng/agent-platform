package com.github.agentos.server.config;

import com.github.agentos.server.security.AuthenticationFilter;
import com.github.agentos.server.security.JwtService;
import com.github.agentos.server.security.RequestIdentityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * REST 接口安全装配。
 *
 * <p>鉴权总开关见 {@link AuthConfiguration#authEnabled}：开启时所有 {@code /api/*}
 * 请求需携带登录签发的 {@code Bearer} 令牌，或兼容脚本调用的 {@code X-API-Key}
 * （{@code agentos.security.api-key}，环境变量 {@code AGENTOS_API_KEY}）；
 * 关闭时全放行，便于本地开发。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** 注册统一鉴权过滤器；总开关关闭时不启用。 */
    @Bean
    FilterRegistrationBean<AuthenticationFilter> authenticationFilter(
            JwtService jwtService,
            boolean authEnabled,
            @Value("${agentos.security.api-key:}") String apiKey,
            @Value("${agentos.security.identity.team-id:default-team}") String teamId) {
        AuthenticationFilter filter = new AuthenticationFilter(jwtService, apiKey, teamId);
        FilterRegistrationBean<AuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setEnabled(authEnabled && filter.enabled());
        if (registration.isEnabled()) {
            LOGGER.info("[auth] authentication enabled for /api/* "
                    + "(jwt={}, api-key={})", jwtService != null, !apiKey.isBlank());
        } else {
            LOGGER.warn("[auth] authentication DISABLED - suitable for local development only");
        }
        return registration;
    }

    /** 在鉴权之后绑定 team/user/role 身份，供 Memory ACL 等业务授权使用。 */
    @Bean
    FilterRegistrationBean<RequestIdentityFilter> requestIdentityFilter(
            @Value("${agentos.security.identity.team-id:default-team}") String teamId,
            @Value("${agentos.security.identity.user-id:default-user}") String userId,
            @Value("${agentos.security.identity.roles:}") String roles,
            @Value("${agentos.security.identity.trust-headers:false}") boolean trustHeaders) {
        FilterRegistrationBean<RequestIdentityFilter> registration =
                new FilterRegistrationBean<>(new RequestIdentityFilter(
                        teamId, userId, roles, trustHeaders));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(2);
        return registration;
    }
}
