package com.github.agentos.server.config;

import com.github.agentos.server.security.AuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 桌面壳跨域配置测试：预检先于鉴权应答，未放行来源被拒绝。 */
class DesktopCorsConfigurationTest {

    @RestController
    static class PingController {

        @GetMapping("/api/ping")
        Map<String, String> ping() {
            return Map.of("status", "ok");
        }
    }

    /** 与生产一致的过滤器顺序：CORS（order=0）在鉴权（order=1）之前。 */
    private static MockMvc mockMvcWithApiKey(String apiKey) {
        CorsFilter corsFilter = new DesktopCorsConfiguration()
                .desktopCorsFilter("tauri://localhost,http://tauri.localhost").getFilter();
        return MockMvcBuilders.standaloneSetup(new PingController())
                .addFilters(corsFilter, new AuthenticationFilter(null, apiKey))
                .build();
    }

    @Test
    void preflightFromDesktopOriginIsAnsweredWithoutApiKey() throws Exception {
        // 预检请求不带 X-API-Key；若被鉴权过滤器拦截，启用鉴权后桌面端会整体失败。
        mockMvcWithApiKey("secret")
                .perform(options("/api/ping")
                        .header(HttpHeaders.ORIGIN, "tauri://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "tauri://localhost"));
    }

    @Test
    void roleUpdatePutPreflightFromDesktopOriginIsAllowed() throws Exception {
        mockMvcWithApiKey("secret")
                .perform(options("/api/ping")
                        .header(HttpHeaders.ORIGIN, "tauri://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "tauri://localhost"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void actualCrossOriginRequestStillRequiresApiKey() throws Exception {
        mockMvcWithApiKey("secret")
                .perform(get("/api/ping").header(HttpHeaders.ORIGIN, "tauri://localhost"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedCrossOriginResponseCarriesAllowOriginHeader() throws Exception {
        mockMvcWithApiKey("secret")
                .perform(get("/api/ping")
                        .header(HttpHeaders.ORIGIN, "tauri://localhost")
                        .header(AuthenticationFilter.API_KEY_HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "tauri://localhost"));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvcWithApiKey("")
                .perform(options("/api/ping")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actualRequestFromUnknownOriginIsNotRejected() throws Exception {
        // 反向代理终结 TLS 后，同源浏览器请求也会带 Origin 头；
        // 实际请求不得 403，只跳过 CORS 头，由浏览器按响应自行裁决。
        mockMvcWithApiKey("")
                .perform(get("/api/ping")
                        .header(HttpHeaders.ORIGIN, "https://agentos.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
