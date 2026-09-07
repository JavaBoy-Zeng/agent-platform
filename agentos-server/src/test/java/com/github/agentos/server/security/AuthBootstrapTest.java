package com.github.agentos.server.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 首次引导与无 ADMIN 自助恢复测试。 */
class AuthBootstrapTest {

    private static final PasswordHasher HASHER = new PasswordHasher(1_000);

    private static AuthBootstrap bootstrap(
            UserStore store, String username, String password) {
        return new AuthBootstrap(store, HASHER, username, password, true);
    }

    @Test
    void createsAdminOnEmptyStore() {
        InMemoryUserStore store = new InMemoryUserStore();
        bootstrap(store, "admin", "bootstrap-pass-1").run(null);
        assertThat(store.count()).isEqualTo(1);
        UserAccount account = store.findByUsername("admin").orElseThrow();
        assertThat(account.isAdmin()).isTrue();
        assertThat(HASHER.verify("bootstrap-pass-1", account.passwordHash())).isTrue();
    }

    @Test
    void leavesStoreUntouchedWhenAdminAlreadyExists() {
        InMemoryUserStore store = new InMemoryUserStore();
        store.create(new UserAccount("root", HASHER.hash("root-pass-123"),
                Set.of("ADMIN", "USER"), null));
        store.create(new UserAccount("alice", HASHER.hash("alice-pass-123"),
                Set.of("USER"), null));
        bootstrap(store, "admin", "bootstrap-pass-1").run(null);
        assertThat(store.count()).isEqualTo(2);
        // 引导密码不得覆盖已有账号的密码。
        assertThat(store.findByUsername("root").orElseThrow().isAdmin()).isTrue();
        assertThat(HASHER.verify("root-pass-123",
                store.findByUsername("root").orElseThrow().passwordHash())).isTrue();
    }

    @Test
    void promotesExistingAccountWhenNoAdminRemains() {
        InMemoryUserStore store = new InMemoryUserStore();
        store.create(new UserAccount("admin", HASHER.hash("old-pass-12345"),
                Set.of("USER"), null));
        bootstrap(store, "admin", "bootstrap-pass-1").run(null);
        UserAccount account = store.findByUsername("admin").orElseThrow();
        assertThat(account.isAdmin()).isTrue();
        // 只补角色，不改密码：改密必须走登录后接口。
        assertThat(HASHER.verify("old-pass-12345", account.passwordHash())).isTrue();
    }

    @Test
    void createsAdminAccountWhenNoAdminRemainsAndAccountMissing() {
        InMemoryUserStore store = new InMemoryUserStore();
        store.create(new UserAccount("alice", HASHER.hash("alice-pass-123"),
                Set.of("USER"), null));
        bootstrap(store, "admin", "bootstrap-pass-1").run(null);
        assertThat(store.count()).isEqualTo(2);
        UserAccount account = store.findByUsername("admin").orElseThrow();
        assertThat(account.isAdmin()).isTrue();
        assertThat(HASHER.verify("bootstrap-pass-1", account.passwordHash())).isTrue();
    }

    @Test
    void failsWhenAuthEnabledWithoutUsersAndPassword() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthBootstrap bootstrap = bootstrap(store, "admin", "");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENTOS_AUTH_ADMIN_PASSWORD");
    }
}
