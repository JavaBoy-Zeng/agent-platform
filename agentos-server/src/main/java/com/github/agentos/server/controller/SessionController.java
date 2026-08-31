package com.github.agentos.server.controller;

import com.github.agentos.kernel.Session;
import com.github.agentos.kernel.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 会话快照查询入口：暴露会话身份与事件增量合并后的结构化状态。 */
@RestController
@RequestMapping("/api/sessions")
public final class SessionController {

    private static final int MAX_LIMIT = 200;

    private final SessionService sessionService;

    /** 创建会话控制器。 */
    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 按最后活跃时间倒序列出服务端已知会话。
     *
     * @param limit 最大返回数量，取值范围 1-200
     */
    @GetMapping
    public List<SessionResponse> sessions(@RequestParam(defaultValue = "50") int limit) {
        validateLimit(limit);
        return sessionService.recent(limit).stream().map(SessionResponse::from).toList();
    }

    /** 按最后活跃时间倒序分页列出会话，供聊天侧栏增量加载。 */
    @GetMapping("/page")
    public SessionPageResponse sessionPage(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        validateLimit(limit);
        long total = sessionService.count();
        List<SessionResponse> items = sessionService.recent(offset, limit).stream()
                .map(SessionResponse::from)
                .toList();
        return new SessionPageResponse(items, total, offset, limit, offset + items.size() < total);
    }

    /** 返回指定会话的当前快照。 */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> session(@PathVariable String sessionId) {
        return sessionService.find(sessionId)
                .map(session -> ResponseEntity.ok(SessionResponse.from(session)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 更新会话的用户可见标题或置顶状态。 */
    @PatchMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> updateSession(
            @PathVariable String sessionId, @RequestBody UpdateSessionRequest request) {
        if (sessionService.find(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request == null || request.title() == null && request.pinned() == null) {
            throw new IllegalArgumentException("title or pinned must be provided");
        }

        Map<String, Object> delta = new LinkedHashMap<>();
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isEmpty() || title.length() > 60) {
                throw new IllegalArgumentException("title must contain between 1 and 60 characters");
            }
            delta.put("displayTitle", title);
        }
        if (request.pinned() != null) {
            delta.put("pinned", request.pinned());
            delta.put("pinnedAt", request.pinned() ? java.time.Instant.now().toString() : "");
        }
        return ResponseEntity.ok(SessionResponse.from(
                sessionService.applyDelta(sessionId, delta)));
    }

    /** 从服务端会话索引删除指定会话快照。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        return sessionService.delete(sessionId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 批量删除服务端会话快照，并返回未找到的会话标识供客户端处理部分失败。 */
    @PostMapping("/batch-delete")
    public BatchDeleteSessionResponse deleteSessions(@RequestBody BatchDeleteSessionRequest request) {
        List<String> sessionIds = request == null || request.sessionIds() == null
                ? List.of()
                : request.sessionIds().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(sessionId -> !sessionId.isEmpty())
                        .distinct()
                        .toList();
        if (sessionIds.isEmpty() || sessionIds.size() > MAX_LIMIT) {
            throw new IllegalArgumentException("sessionIds must contain between 1 and 200 unique values");
        }

        List<String> notFound = sessionIds.stream()
                .filter(sessionId -> !sessionService.delete(sessionId))
                .toList();
        return new BatchDeleteSessionResponse(
                sessionIds.size(), sessionIds.size() - notFound.size(), notFound);
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

    /** 会话分页视图。 */
    public record SessionPageResponse(
            List<SessionResponse> items, long total, int offset, int limit, boolean hasMore) {
    }

    /** 会话可编辑字段；未提供的字段保持不变。 */
    public record UpdateSessionRequest(String title, Boolean pinned) {
    }

    /** 批量删除会话请求。 */
    public record BatchDeleteSessionRequest(List<String> sessionIds) {
    }

    /** 批量删除会话结果。 */
    public record BatchDeleteSessionResponse(int requested, int deleted, List<String> notFound) {
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
