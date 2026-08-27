package com.github.agentos.server.config;

import com.github.agentos.server.security.AuthBootstrap;
import com.github.agentos.server.security.JwtService;
import com.github.agentos.server.security.LoginRateLimiter;
import com.github.agentos.server.security.PasswordHasher;
import com.github.agentos.server.security.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 登录鉴权组件装配：密码哈希、JWT、登录限速与首次引导。
 *
 * <p>{@code agentos.auth.secret}（环境变量 {@code AGENTOS_AUTH_SECRET}）为 JWT
 * 签名密钥，留空时每次启动随机生成——重启后所有已登录会话失效。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthConfiguration.class);

    /** 创建 PBKDF2 密码哈希器。 */
    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    /** 创建 HS256 JWT 服务。 */
    @Bean
    JwtService jwtService(
            @Value("${agentos.auth.secret:}") String secret,
            @Value("${agentos.auth.token-ttl:30d}") Duration tokenTtl) {
        if (secret.isBlank()) {
            LOGGER.warn("[auth] agentos.auth.secret is empty; "
                    + "using an ephemeral random key - all logins expire on restart");
            return new JwtService(JwtService.randomSecret(), tokenTtl);
        }
        return new JwtService(secret.trim().getBytes(StandardCharsets.UTF_8), tokenTtl);
    }

    /** 创建登录失败限速器。 */
    @Bean
    LoginRateLimiter loginRateLimiter(
            @Value("${agentos.auth.max-login-failures:5}") int maxLoginFailures,
            @Value("${agentos.auth.lockout:15m}") Duration lockout) {
        return new LoginRateLimiter(maxLoginFailures, lockout);
    }

    /**
     * 鉴权总开关：显式配置优先；未配置时自动探测——
     * 配置了引导密码或库里已有用户即启用，保证本地零配置开发不受影响。
     */
    @Bean
    boolean authEnabled(
            @Value("${agentos.auth.enabled:}") String explicit,
            @Value("${agentos.auth.admin-password:}") String adminPassword,
            UserStore userStore) {
        if ("true".equalsIgnoreCase(explicit.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(explicit.trim())) {
            return false;
        }
        return !adminPassword.isBlank() || userStore.count() > 0;
    }

    /** 创建首次启动的 ADMIN 引导器。 */
    @Bean
    AuthBootstrap authBootstrap(
            UserStore userStore,
            PasswordHasher passwordHasher,
            @Value("${agentos.auth.admin-user:admin}") String adminUser,
            @Value("${agentos.auth.admin-password:}") String adminPassword,
            boolean authEnabled) {
        return new AuthBootstrap(
                userStore, passwordHasher, adminUser, adminPassword, authEnabled);
    }
}
