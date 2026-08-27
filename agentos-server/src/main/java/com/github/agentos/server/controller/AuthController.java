package com.github.agentos.server.controller;

import com.github.agentos.server.security.JwtService;
import com.github.agentos.server.security.LoginRateLimiter;
import com.github.agentos.server.security.PasswordHasher;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.UserAccount;
import com.github.agentos.server.security.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 登录与会话接口：{@code /api/auth/*}。
 *
 * <p>login 为放行路径，其余接口经 {@code AuthenticationFilter} 鉴权后到达；
 * 用户管理接口要求 JWT 身份携带 ADMIN 角色。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserStore userStore;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;

    /** 创建认证控制器。 */
    public AuthController(
            UserStore userStore,
            PasswordHasher passwordHasher,
            JwtService jwtService,
            LoginRateLimiter rateLimiter) {
        this.userStore = userStore;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
    }

    /** 登录并签发访问令牌。 */
    @PostMapping("/login")
    Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        String limiterKey = username + "|" + clientAddress(httpRequest);

        if (username.isEmpty() || password.isEmpty()) {
            throw new UnauthorizedException("用户名或密码不正确");
        }
        if (rateLimiter.isLocked(limiterKey)) {
            throw new LockedOutException();
        }

        UserAccount account = userStore.findByUsername(username).orElse(null);
        if (account == null || !passwordHasher.verify(password, account.passwordHash())) {
            rateLimiter.recordFailure(limiterKey);
            throw new UnauthorizedException("用户名或密码不正确");
        }

        rateLimiter.reset(limiterKey);
        String token = jwtService.issue(account.username(), account.roles());
        JwtService.AuthenticatedUser parsed = jwtService.parse(token);

        return Map.of(
                "token", token,
                "expiresAt", parsed.expiresAt().toString(),
                "user", userView(account));
    }

    /** 当前登录身份。 */
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        RequestIdentity identity = RequestIdentity.from(request);
        return userStore.findByUsername(identity.userId())
                .map(account -> Map.<String, Object>of(
                        "username", account.username(), "roles", account.roles()))
                .orElseGet(() -> Map.of(
                        "username", identity.userId(), "roles", identity.roles()));
    }

    /** 修改本人密码。 */
    @PostMapping("/change-password")
    Map<String, Object> changePassword(
            @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        String username = RequestIdentity.from(httpRequest).userId();
        UserAccount account = userStore.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("用户不存在"));
        if (!passwordHasher.verify(
                request.oldPassword() == null ? "" : request.oldPassword(),
                account.passwordHash())) {
            // 原密码错误属于表单校验失败：用 400 区别于过滤器的会话级 401，避免前端误触发全局登出。
            throw new BadRequestException("原密码不正确");
        }
        String newPassword = request.newPassword() == null ? "" : request.newPassword();
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        userStore.updatePasswordHash(username, passwordHasher.hash(newPassword));
        return Map.of("status", "ok");
    }

    /** 用户列表（仅 ADMIN）。 */
    @GetMapping("/users")
    List<Map<String, Object>> users(HttpServletRequest request) {
        requireAdmin(request);
        return userStore.list().stream().map(this::userView).toList();
    }

    /** 新建用户（仅 ADMIN）。 */
    @PostMapping("/users")
    Map<String, Object> createUser(@RequestBody CreateUserRequest request, HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isEmpty()) {
            throw new BadRequestException("用户名不能为空");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        Set<String> roles = new java.util.LinkedHashSet<>();
        roles.add(UserAccount.ROLE_USER);
        if (request.roles() != null) {
            roles.addAll(request.roles());
        }
        try {
            userStore.create(new UserAccount(username, passwordHasher.hash(password), roles, null));
        } catch (IllegalStateException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "用户已存在");
        }
        return userStore.findByUsername(username).map(this::userView).orElseThrow();
    }

    /** 完整替换用户角色（仅 ADMIN）；USER 为所有账户的基础角色。 */
    @PutMapping("/users/{username}/roles")
    Map<String, Object> updateRoles(
            @PathVariable String username,
            @RequestBody UpdateRolesRequest request,
            HttpServletRequest httpRequest) {
        RequestIdentity identity = RequestIdentity.from(httpRequest);
        requireAdmin(httpRequest);
        String target = username == null ? "" : username.trim();
        UserAccount account = userStore.findByUsername(target)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "用户不存在"));
        Set<String> roles = new java.util.LinkedHashSet<>();
        roles.add(UserAccount.ROLE_USER);
        if (request.roles() != null) roles.addAll(request.roles());
        UserAccount normalized = new UserAccount(
                account.username(), account.passwordHash(), roles, account.createdAt());
        boolean removesAdmin = account.isAdmin()
                && !normalized.roles().contains(UserAccount.ROLE_ADMIN);
        if (target.equals(identity.userId()) && removesAdmin) {
            throw new BadRequestException("不能移除当前登录用户的 ADMIN 角色");
        }
        if (removesAdmin
                && userStore.list().stream().filter(UserAccount::isAdmin).count() <= 1) {
            throw new BadRequestException("不能移除最后一个 ADMIN 角色");
        }
        if (!userStore.updateRoles(target, normalized.roles())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "用户不存在");
        }
        return userStore.findByUsername(target).map(this::userView).orElseThrow();
    }

    /** 删除用户（仅 ADMIN；不可删除自己与最后一个 ADMIN）。 */
    @DeleteMapping("/users/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteUser(@PathVariable String username, HttpServletRequest request) {
        RequestIdentity identity = RequestIdentity.from(request);
        requireAdmin(request);
        String target = username == null ? "" : username.trim();
        if (target.equals(identity.userId())) {
            throw new BadRequestException("不能删除当前登录用户");
        }
        UserAccount account = userStore.findByUsername(target)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "用户不存在"));
        if (account.isAdmin()
                && userStore.list().stream().filter(UserAccount::isAdmin).count() <= 1) {
            throw new BadRequestException("不能删除最后一个 ADMIN 用户");
        }
        userStore.delete(target);
    }

    private Map<String, Object> userView(UserAccount account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("username", account.username());
        view.put("roles", account.roles());
        view.put("createdAt", account.createdAt().toString());
        return view;
    }

    private static void requireAdmin(HttpServletRequest request) {
        if (!RequestIdentity.from(request).roles().contains(UserAccount.ROLE_ADMIN)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "需要 ADMIN 角色");
        }
    }

    private static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }

    /** 修改密码请求体。 */
    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    /** 新建用户请求体。 */
    public record CreateUserRequest(String username, String password, Set<String> roles) {
    }

    /** 用户角色完整替换请求。 */
    public record UpdateRolesRequest(Set<String> roles) {
    }

    /** 401：凭据无效。 */
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static final class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) {
            super(message);
        }
    }

    /** 400：请求不合法。 */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    /** 429：登录锁定中。 */
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    static final class LockedOutException extends RuntimeException {
        LockedOutException() {
            super("失败次数过多，请稍后再试");
        }
    }
}
