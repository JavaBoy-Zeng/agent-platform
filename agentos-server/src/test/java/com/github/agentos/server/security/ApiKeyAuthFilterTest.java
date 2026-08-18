package com.github.agentos.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API Key 鉴权过滤器集成测试。
 *
 * <p>启用密钥后：无密钥/错误密钥访问 {@code /api/*} 返回 401，
 * 正确密钥放行到业务层。</p>
 */
@SpringBootTest(properties = {
        "agentos.security.api-key=test-secret-key",
        "agentos.memory.mode=memory"
})
@TestPropertySource(properties = "agentos.security.api-key=test-secret-key")
class ApiKeyAuthFilterTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterRegistrationBean<ApiKeyAuthFilter> registration;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // webAppContextSetup 不会自动应用 FilterRegistrationBean，需显式挂载到 /api/*。
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(registration.getFilter(), "/api/*")
                .build();
    }

    @Test
    void filterIsEnabledWhenKeyConfigured() {
        assertThat(registration.isEnabled()).isTrue();
        assertThat(registration.getFilter()).isInstanceOf(ApiKeyAuthFilter.class);
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(post("/api/agents/runs")
                        .contentType("application/json")
                        .content("{\"input\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing or invalid API key"));
    }

    @Test
    void rejectsRequestWithWrongApiKey() throws Exception {
        mockMvc.perform(post("/api/agents/runs")
                        .contentType("application/json")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key")
                        .content("{\"input\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsRequestWithCorrectApiKey() throws Exception {
        // 寒暄短路路径不触发 LLM：鉴权通过后应进入业务层并正常完成（该端点返回 201）。
        mockMvc.perform(post("/api/agents/runs")
                        .contentType("application/json")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, "test-secret-key")
                        .content("{\"input\":\"你好\"}"))
                .andExpect(status().isCreated());
    }
}
