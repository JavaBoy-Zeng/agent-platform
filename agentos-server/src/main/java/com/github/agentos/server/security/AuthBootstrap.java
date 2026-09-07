package com.github.agentos.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 首次启动引导：users 为空时用环境变量创建 ADMIN 账号。
 *
 * <p>{@code AGENTOS_AUTH_ADMIN_PASSWORD} 为空且启用鉴权且无任何用户时，
 * 直接启动失败并给出明确指引；表非空时忽略环境变量（改密请走登录后接口）。
 * 表非空但没有任何 ADMIN（历史数据或角色被误删）时，恢复引导账号的管理员角色，
 * 避免管理接口永久 403 且无从自助修复。</p>
 */
public final class AuthBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthBootstrap.class);

    private static final Set<String> BOOTSTRAP_ROLES = Set.of(
            UserAccount.ROLE_ADMIN, "MEMORY_ADMIN",
            UserAccount.ROLE_WORKSPACE, UserAccount.ROLE_USER);

    private final UserStore userStore;
    private final PasswordHasher passwordHasher;
    private final String adminUsername;
    private final String adminPassword;
    private final boolean authEnabled;

    /** 创建引导器。 */
    public AuthBootstrap(
            UserStore userStore,
            PasswordHasher passwordHasher,
            String adminUsername,
            String adminPassword,
            boolean authEnabled) {
        this.userStore = userStore;
        this.passwordHasher = passwordHasher;
        this.adminUsername = adminUsername == null || adminUsername.isBlank()
                ? "admin" : adminUsername.trim();
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        this.authEnabled = authEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminPassword.isEmpty()) {
            if (userStore.count() == 0) {
                userStore.create(new UserAccount(
                        adminUsername, passwordHasher.hash(adminPassword),
                        BOOTSTRAP_ROLES, null));
                LOGGER.info("[auth] bootstrapped admin user '{}'", adminUsername);
                return;
            }
            if (userStore.list().stream().noneMatch(UserAccount::isAdmin)) {
                restoreAdmin();
                LOGGER.warn("[auth] no admin user in store; "
                        + "restored admin role for '{}'", adminUsername);
            }
            return;
        }
        if (authEnabled && userStore.count() == 0) {
            throw new IllegalStateException(
                    "鉴权已启用但没有任何用户：请配置 AGENTOS_AUTH_ADMIN_PASSWORD 完成首次引导");
        }
    }

    /** 已有用户但无任何 ADMIN 时：引导账号存在则补齐管理员角色，否则按引导密码创建。 */
    private void restoreAdmin() {
        userStore.findByUsername(adminUsername).ifPresentOrElse(
                account -> {
                    Set<String> roles = new LinkedHashSet<>(account.roles());
                    roles.addAll(BOOTSTRAP_ROLES);
                    userStore.updateRoles(adminUsername, roles);
                },
                () -> userStore.create(new UserAccount(
                        adminUsername, passwordHasher.hash(adminPassword),
                        BOOTSTRAP_ROLES, null)));
    }
}
