package com.github.agentos.server.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 桌面壳（Tauri）等跨域客户端的 CORS 装配。
 *
 * <p>Tauri 页面的 origin 为 {@code tauri://localhost}（macOS/Linux；
 * Windows WebView2 下为 {@code http://tauri.localhost}），直连 server 时
 * 所有 {@code /api} 请求——含 SSE——均为跨域请求。本过滤器注册在
 * API Key 鉴权过滤器之前：CORS 预检请求不携带 {@code X-API-Key}，
 * 必须由本过滤器短路应答，否则启用鉴权后跨域请求会整体失败。</p>
 *
 * <p>默认仅放行桌面 WebView 来源；可用 {@code agentos.cors.allowed-origins}
 * （环境变量 {@code AGENTOS_CORS_ALLOWED_ORIGINS}）覆盖，值为 {@code *}
 * 时放行任意来源。</p>
 *
 * <p>反向代理部署（如 Cloudflare Tunnel + Caddy 终结 TLS）下，浏览器对同源
 * 请求也会携带 {@code Origin} 头，而新版 Spring 会把带 Origin 的请求按 CORS
 * 处理；若对这类请求直接 403，会误杀同源流量。因此实际（非预检）请求遇到
 * 未放行来源时只跳过 CORS 头、放行到业务链路，由浏览器依据响应头自行裁决；
 * 预检请求仍严格拒绝。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DesktopCorsConfiguration {

    /** 默认放行的桌面 WebView 来源。 */
    private static final String DEFAULT_ORIGINS =
            "tauri://localhost,http://tauri.localhost";

    /**
     * 非预检请求不因来源未放行而拒绝：不加 CORS 头直接放行，
     * 避免经反向代理的同源请求被误判为跨域而 403。
     */
    private static final class NonRejectingCorsProcessor extends DefaultCorsProcessor {

        @Override
        protected boolean handleInternal(
                ServerHttpRequest request,
                ServerHttpResponse response,
                CorsConfiguration config,
                boolean preFlightRequest) throws IOException {
            if (!preFlightRequest
                    && checkOrigin(config, request.getHeaders().getOrigin()) == null) {
                return true;
            }
            return super.handleInternal(request, response, config, preFlightRequest);
        }
    }

    /**
     * 注册作用于 {@code /api/**} 的 CORS 过滤器。
     *
     * <p>order 设为 0，先于鉴权过滤器（order=1/2）执行，保证预检可被直接应答。
     * 不开启凭据（allowCredentials），客户端通过 {@code X-API-Key} 头认证。</p>
     *
     * @param allowedOrigins 允许的来源列表，逗号分隔；{@code *} 表示全部放行
     * @return CORS 过滤器注册
     */
    @Bean
    FilterRegistrationBean<CorsFilter> desktopCorsFilter(
            @Value("${agentos.cors.allowed-origins:" + DEFAULT_ORIGINS + "}")
            String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 产物下载接口用该头携带文件名，桌面端 fetch 下载时需要读取
        config.addExposedHeader("Content-Disposition");
        config.setMaxAge(3600L);
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .forEach(origin -> {
                    if ("*".equals(origin)) {
                        config.addAllowedOriginPattern("*");
                    } else {
                        config.addAllowedOrigin(origin);
                    }
                });
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        CorsFilter corsFilter = new CorsFilter(source);
        corsFilter.setCorsProcessor(new NonRejectingCorsProcessor());
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(corsFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
