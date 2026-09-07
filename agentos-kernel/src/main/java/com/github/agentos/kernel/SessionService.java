package com.github.agentos.kernel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话生命周期与状态的读写协议。
 *
 * <p>Runtime 在发布携带 {@link EventActions#stateDelta()} 的事件时调用
 * {@link #applyDelta(String, Map)} 完成状态合并；业务方通过 {@link #find(String)}
 * 读取当前快照。实现必须保证同一会话的读改写串行化。</p>
 */
public interface SessionService {

    /** 返回指定会话，不存在时以给定用户创建并保存。 */
    Session getOrCreate(String sessionId, String userId);

    /** 查找指定会话。 */
    Optional<Session> find(String sessionId);

    /** 仅当会话属于指定用户时返回快照。 */
    default Optional<Session> findByUser(String sessionId, String userId) {
        return find(sessionId).filter(session -> session.userId().equals(userId));
    }

    /**
     * 按最后活跃时间倒序返回会话快照。
     *
     * <p>供管理面板列出服务端已知会话；{@code limit} 之外的会话不返回。</p>
     *
     * @param limit 最大返回数量，必须为正数
     */
    List<Session> recent(int limit);

    /**
     * 按最后活跃时间倒序分页返回会话快照。
     *
     * @param offset 跳过的会话数量，必须大于等于 0
     * @param limit 最大返回数量，必须为正数
     */
    default List<Session> recent(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        long requested = (long) offset + limit;
        int fetchLimit = requested > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) requested;
        return recent(fetchLimit).stream().skip(offset).limit(limit).toList();
    }

    /** 返回当前保存的会话总数。 */
    default long count() {
        return recent(Integer.MAX_VALUE).size();
    }

    /** 按最后活跃时间倒序分页返回指定用户的会话。 */
    default List<Session> recentByUser(String userId, int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return recent(Integer.MAX_VALUE).stream()
                .filter(session -> session.userId().equals(userId))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    /** 返回指定用户保存的会话总数。 */
    default long countByUser(String userId) {
        return recent(Integer.MAX_VALUE).stream()
                .filter(session -> session.userId().equals(userId))
                .count();
    }

    /** 删除指定会话快照；不存在时返回 false。 */
    default boolean delete(String sessionId) {
        return false;
    }

    /** 仅删除属于指定用户的会话；不存在或归属不匹配时返回 false。 */
    default boolean deleteByUser(String sessionId, String userId) {
        return findByUser(sessionId, userId).isPresent() && delete(sessionId);
    }

    /** 仅对指定用户持有的未删除会话原子合并状态。 */
    default Optional<Session> applyDeltaByUser(
            String sessionId, String userId, Map<String, Object> delta) {
        return findByUser(sessionId, userId).map(ignored -> applyDelta(sessionId, delta));
    }

    /**
     * 把状态增量合并进会话并刷新活跃时间。
     *
     * @param sessionId 会话标识
     * @param delta 状态增量，空 Map 仅刷新活跃时间
     * @return 合并后的会话快照
     */
    Session applyDelta(String sessionId, Map<String, Object> delta);
}
