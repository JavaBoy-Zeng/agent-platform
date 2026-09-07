package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次持续对话的会话实体。
 *
 * <p>会话由领域事件序列构成；{@link SessionState} 是事件增量合并后的当前快照。
 * 会话身份（sessionId）与其结构化状态、记忆（跨会话）三者分离。</p>
 *
 * @param sessionId 会话标识
 * @param userId 所属用户标识
 * @param createdAt 创建时间
 * @param lastActiveAt 最近活跃时间
 * @param state 当前结构化状态
 * @param deletedAt 软删除时间；未删除时为 null
 */
public record Session(
        String sessionId,
        String userId,
        Instant createdAt,
        Instant lastActiveAt,
        SessionState state,
        Instant deletedAt) {

    /** 兼容未显式传入软删除时间的现有调用。 */
    public Session(
            String sessionId, String userId, Instant createdAt,
            Instant lastActiveAt, SessionState state) {
        this(sessionId, userId, createdAt, lastActiveAt, state, null);
    }

    /** 创建并校验会话。 */
    public Session {
        sessionId = requireText(sessionId, "sessionId");
        userId = requireText(userId, "userId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        lastActiveAt = Objects.requireNonNull(lastActiveAt, "lastActiveAt must not be null");
        state = state == null ? SessionState.empty() : state;
    }

    /** 以当前时间创建新会话。 */
    public static Session create(String sessionId, String userId) {
        Instant now = Instant.now();
        return new Session(sessionId, userId, now, now, SessionState.empty(), null);
    }

    /** 返回替换状态后的新会话。 */
    public Session withState(SessionState newState) {
        return new Session(sessionId, userId, createdAt, lastActiveAt, newState, deletedAt);
    }

    /** 返回刷新活跃时间后的新会话。 */
    public Session touch(Instant time) {
        return new Session(sessionId, userId, createdAt, time, state, deletedAt);
    }

    /** 返回保留原始归属的软删除墓碑。 */
    public Session softDelete(Instant time) {
        return new Session(sessionId, userId, createdAt, lastActiveAt, state,
                Objects.requireNonNull(time, "deletedAt must not be null"));
    }

    /** 会话是否已软删除。 */
    public boolean deleted() {
        return deletedAt != null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
