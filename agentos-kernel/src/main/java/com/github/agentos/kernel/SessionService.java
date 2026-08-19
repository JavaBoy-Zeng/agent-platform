package com.github.agentos.kernel;

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

    /**
     * 把状态增量合并进会话并刷新活跃时间。
     *
     * @param sessionId 会话标识
     * @param delta 状态增量，空 Map 仅刷新活跃时间
     * @return 合并后的会话快照
     */
    Session applyDelta(String sessionId, Map<String, Object> delta);
}
