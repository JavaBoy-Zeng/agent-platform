package com.github.agentos.server.security;

import com.github.agentos.kernel.Session;
import com.github.agentos.kernel.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** 会话资源的统一归属校验；管理员不绕过用户隔离。 */
@Component
public final class SessionAuthorization {

    private final SessionService sessions;

    public SessionAuthorization(SessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    /** 返回当前请求所属的活跃会话；越权与不存在统一按 404 处理。 */
    public Session requireOwned(String sessionId, HttpServletRequest request) {
        String userId = RequestIdentity.from(request).userId();
        return sessions.findByUser(sessionId, userId).orElseThrow(SessionAuthorization::notFound);
    }

    /** 当前请求是否持有指定活跃会话；软删除会话返回 false。 */
    public boolean owns(String sessionId, HttpServletRequest request) {
        return sessions.findByUser(sessionId, RequestIdentity.from(request).userId()).isPresent();
    }

    /** 为当前请求创建新会话，或确认已有会话归属。 */
    public Session claim(String sessionId, HttpServletRequest request) {
        String userId = RequestIdentity.from(request).userId();
        try {
            return sessions.getOrCreate(sessionId, userId);
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
    }
}
