package com.github.agentos.server.controller;

import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.security.InMemoryUserStore;
import com.github.agentos.server.security.PasswordHasher;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.UserAccount;
import com.github.agentos.server.security.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 模型管理接口的 ADMIN 门禁测试：以用户库实时角色为准。 */
class ModelProviderControllerTest {

    private UserStore userStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userStore = new InMemoryUserStore();
        PasswordHasher hasher = new PasswordHasher(1_000);
        userStore.create(new UserAccount(
                "admin", hasher.hash("admin-pass-123"),
                Set.of("ADMIN", "USER"), null));
        userStore.create(new UserAccount(
                "alice", hasher.hash("alice-pass-123"), Set.of("USER"), null));

        ModelProviderService service = new ModelProviderService(
                "memory", null, new ObjectMapper(), "");
        mvc = MockMvcBuilders.standaloneSetup(
                        new ModelProviderController(service, userStore, true))
                .build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor identity(
            String username, Set<String> roles) {
        return request -> {
            request.setAttribute(RequestIdentity.REQUEST_ATTRIBUTE,
                    new RequestIdentity("default-team", username, roles));
            return request;
        };
    }

    @Test
    void snapshotAllowsStoredAdminWithStaleNonAdminToken() throws Exception {
        // 管理员在令牌签发后才被授予 ADMIN：实时角色生效，不再等令牌过期。
        mvc.perform(get("/api/model-management").with(identity("admin", Set.of("USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray());
    }

    @Test
    void snapshotRejectsRevokedAdminEvenWithAdminToken() throws Exception {
        // 令牌仍带 ADMIN 但库中角色已被回收：立即拒绝。
        mvc.perform(get("/api/model-management").with(identity("alice", Set.of("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void snapshotAllowsAnyoneWhenAuthDisabled() throws Exception {
        ModelProviderService service = new ModelProviderService(
                "memory", null, new ObjectMapper(), "");
        MockMvc open = MockMvcBuilders.standaloneSetup(
                        new ModelProviderController(service, userStore, false))
                .build();
        open.perform(get("/api/model-management"))
                .andExpect(status().isOk());
    }
}
