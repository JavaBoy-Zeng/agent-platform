package com.github.agentos.server.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 统一鉴权过滤器测试：Bearer 与 X-API-Key 双凭据及放行路径。 */
class AuthenticationFilterTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @RestController
    static class ProbeController {

        @GetMapping("/api/ping")
        Map<String, Object> ping(jakarta.servlet.http.HttpServletRequest request) {
            RequestIdentity identity = RequestIdentity.from(request);
            return Map.of("user", identity.userId(), "roles", identity.roles());
        }

        @GetMapping("/api/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }

    private static JwtService jwtService() {
        return new JwtService(SECRET, Duration.ofDays(1));
    }

    private MockMvc mockMvc(AuthenticationFilter filter) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(filter)
                .build();
    }

    @Test
    void validBearerTokenBindsUserIdentity() throws Exception {
        JwtService service = jwtService();
        String token = service.issue("whale", Set.of("ADMIN", "MEMORY_ADMIN"));
        mockMvc(new AuthenticationFilter(service, ""))
                .perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("whale"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ADMIN", "MEMORY_ADMIN")));
    }

    @Test
    void invalidBearerTokenIsRejected() throws Exception {
        JwtService service = jwtService();
        String token = service.issue("whale", Set.of());
        String tampered = token.substring(0, token.length() - 2) + "xx";
        mockMvc(new AuthenticationFilter(service, ""))
                .perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingCredentialsIsRejected() throws Exception {
        mockMvc(new AuthenticationFilter(jwtService(), ""))
                .perform(get("/api/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsupportedAuthorizationSchemeIsRejected() throws Exception {
        mockMvc(new AuthenticationFilter(jwtService(), ""))
                .perform(get("/api/ping").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validApiKeyPassesThrough() throws Exception {
        mockMvc(new AuthenticationFilter(null, "secret"))
                .perform(get("/api/ping").header(AuthenticationFilter.API_KEY_HEADER, "secret"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidApiKeyIsRejected() throws Exception {
        mockMvc(new AuthenticationFilter(null, "secret"))
                .perform(get("/api/ping").header(AuthenticationFilter.API_KEY_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openPathsBypassAuthentication() throws Exception {
        MockMvc mvc = mockMvc(new AuthenticationFilter(jwtService(), ""));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void disabledFilterPassesEverything() throws Exception {
        mockMvc(new AuthenticationFilter(null, ""))
                .perform(get("/api/ping"))
                .andExpect(status().isOk());
    }
}
