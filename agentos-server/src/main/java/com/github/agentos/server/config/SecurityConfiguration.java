package com.github.agentos.server.config;

import com.github.agentos.server.security.ApiKeyAuthFilter;
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
 * <p>{@code agentos.security.api-key}（环境变量 {@code AGENTOS_API_KEY}）非空时，
 * 所有 {@code /api/*} 请求必须携带匹配的 {@code X-API-Key} 请求头；
 * 留空时鉴权关闭，便于本地开发。上线 run_command 等高危工具前必须配置。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** 注册 API Key 鉴权过滤器；密钥留空时不启用。 */
    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            @Value("${agentos.security.api-key:}") String apiKey) {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKey);
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setEnabled(filter.enabled());
        if (filter.enabled()) {
            LOGGER.info("[auth] API key authentication enabled for /api/*");
        } else {
            LOGGER.warn("[auth] API key authentication DISABLED (agentos.security.api-key is empty)");
        }
        return registration;
    }

    /** 在 API Key 校验之后绑定 team/user/role 身份，供 Memory ACL 等业务授权使用。 */
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
