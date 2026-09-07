package com.github.agentos.server.controller;

import com.github.agentos.server.security.InMemoryUserStore;
import com.github.agentos.server.security.JwtService;
import com.github.agentos.server.security.LoginRateLimiter;
import com.github.agentos.server.security.PasswordHasher;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.UserAccount;
import com.github.agentos.server.security.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录、改密与用户管理接口测试。 */
class AuthControllerTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private UserStore userStore;
    private PasswordHasher passwordHasher;
    private JwtService jwtService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userStore = new InMemoryUserStore();
        passwordHasher = new PasswordHasher(1_000);
        jwtService = new JwtService(SECRET, Duration.ofDays(30));
        userStore.create(new UserAccount(
                "admin", passwordHasher.hash("admin-pass-123"),
                Set.of("ADMIN", "MEMORY_ADMIN", "USER"), null));
        userStore.create(new UserAccount(
                "alice", passwordHasher.hash("alice-pass-123"),
                Set.of("USER"), null));
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                        userStore, passwordHasher, jwtService,
                        new LoginRateLimiter(3, Duration.ofMinutes(15))))
                .build();
    }

    private static RequestIdentity identity(String username, Set<String> roles) {
        return new RequestIdentity("default-team", username, roles);
    }

    @Test
    void loginSuccessReturnsUsableToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void consecutiveFailuresLockLogin() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alice\",\"password\":\"bad-pass-123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // 第 4 次尝试即使密码正确也被锁定拒绝。
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-pass-123\"}"))
                .andExpect(status().isTooManyRequests());
        // 其他账户不受影响。
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void meReturnsFreshStoredRoles() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void changePasswordValidatesOldAndLength() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong-pass\",\"newPassword\":\"new-pass-456\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/change-password")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"alice-pass-123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/change-password")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"alice-pass-123\",\"newPassword\":\"new-pass-456\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"new-pass-456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void userManagementRequiresAdmin() throws Exception {
        mvc.perform(get("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void userManagementHonorsStoredRolesOverStaleTokenClaims() throws Exception {
        // 授予 alice ADMIN 后，其旧令牌（claims 仍为 USER）立即获得管理权限。
        userStore.updateRoles("alice", Set.of("ADMIN", "USER"));
        mvc.perform(get("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        // 回收后，仍带 ADMIN claims 的旧令牌立即失效。
        userStore.updateRoles("alice", Set.of("USER"));
        mvc.perform(get("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUserValidatesAndRejectsDuplicates() throws Exception {
        mvc.perform(post("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(post("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob-pass-123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carl\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("密码至少 8 位"));

        mvc.perform(post("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"   \",\"password\":\"valid-pass-123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("用户名不能为空"));

        mvc.perform(post("/api/auth/users")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dana\",\"password\":\"dana-pass-123\",\"roles\":[\"WORKSPACE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(
                        org.hamcrest.Matchers.hasItems("USER", "WORKSPACE")));
    }

    @Test
    void deleteUserProtectsSelfAndLastAdmin() throws Exception {
        mvc.perform(delete("/api/auth/users/admin")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/auth/users/alice")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN"))))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/auth/users/alice")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanGrantAndRevokeWorkspaceRole() throws Exception {
        mvc.perform(put("/api/auth/users/alice/roles")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"WORKSPACE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles").value(
                        org.hamcrest.Matchers.hasItems("USER", "WORKSPACE")));
        mvc.perform(get("/api/auth/me")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(
                        org.hamcrest.Matchers.hasItem("WORKSPACE")));
        mvc.perform(put("/api/auth/users/alice/roles")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void roleUpdateRequiresAdminAndProtectsCurrentAdmin() throws Exception {
        mvc.perform(put("/api/auth/users/admin/roles")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("alice", Set.of("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/users/admin/roles")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isBadRequest());

        // 即使请求身份来自另一个管理员，也不能移除用户库中的最后一个 ADMIN。
        mvc.perform(put("/api/auth/users/admin/roles")
                        .requestAttr(RequestIdentity.REQUEST_ATTRIBUTE,
                                identity("external-admin", Set.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isBadRequest());
    }
}
