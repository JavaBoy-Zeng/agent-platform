package com.github.agentos.server.controller;

import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.UserStore;
import com.github.agentos.server.settings.NonAdminCallLimit;
import com.github.agentos.server.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理员运行时配置接口：当前仅暴露非管理员模型调用限频。
 *
 * <p>PUT 仅 ADMIN 可用，实时改写 {@link SettingsService#NON_ADMIN_CALL_LIMIT}，
 * 立即对随后发起的非管理员调用生效。</p>
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SettingsService settings;
    private final UserStore userStore;
    private final ObjectMapper objectMapper;

    public AdminSettingsController(
            SettingsService settings, UserStore userStore, ObjectMapper objectMapper) {
        this.settings = settings;
        this.userStore = userStore;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/non-admin-call-limit")
    public Map<String, Object> readLimit(HttpServletRequest request) {
        // 读取对所有登录用户开放，方便 UI 在被降级时也能立刻看到当前策略。
        // 仅在持久层缺省时回退到默认策略。
        NonAdminCallLimit stored = settings.read(SettingsService.NON_ADMIN_CALL_LIMIT)
                .map(text -> NonAdminCallLimit.fromJson(text, objectMapper))
                .orElseGet(NonAdminCallLimit::defaults);
        return toView(stored);
    }

    @PutMapping("/non-admin-call-limit")
    public Map<String, Object> updateLimit(
            @RequestBody NonAdminCallLimit body, HttpServletRequest request) {
        RequestIdentity identity = RequestIdentity.from(request);
        if (!identity.isAdmin(userStore)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要 ADMIN 角色");
        }
        NonAdminCallLimit normalized = body == null ? NonAdminCallLimit.defaults() : body.orDefault();
        settings.write(
                SettingsService.NON_ADMIN_CALL_LIMIT,
                normalized.toJson(objectMapper),
                identity.userId());
        return toView(normalized);
    }

    private static Map<String, Object> toView(NonAdminCallLimit value) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", value.enabled());
        view.put("maxCalls", value.enabled() ? value.maxCalls() : 0);
        view.put("windowSeconds", value.enabled() ? value.windowSeconds() : 0);
        return view;
    }
}