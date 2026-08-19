package com.github.agentos.server.controller;

import com.github.agentos.kernel.Session;
import com.github.agentos.kernel.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话快照查询入口：暴露会话身份与事件增量合并后的结构化状态。 */
@RestController
@RequestMapping("/api/sessions")
public final class SessionController {

    private final SessionService sessionService;

    /** 创建会话控制器。 */
    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 返回指定会话的当前快照。 */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> session(@PathVariable String sessionId) {
        return sessionService.find(sessionId)
                .map(session -> ResponseEntity.ok(SessionResponse.from(session)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 会话快照视图。 */
    public record SessionResponse(
            String sessionId, String userId, String createdAt, String lastActiveAt,
            List<String> stateKeys, java.util.Map<String, Object> state) {

        /** 从领域实体构造视图。 */
        public static SessionResponse from(Session session) {
            return new SessionResponse(
                    session.sessionId(),
                    session.userId(),
                    session.createdAt().toString(),
                    session.lastActiveAt().toString(),
                    List.copyOf(session.state().asMap().keySet()),
                    session.state().asMap());
        }
    }
}
