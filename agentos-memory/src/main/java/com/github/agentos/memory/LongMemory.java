package com.github.agentos.memory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内长期记忆存储。
 *
 * <p>当前实现主要用于演示和本地运行，数据不会持久化。生产环境应将该适配器替换为
 * 数据库、对象存储或向量数据库实现。</p>
 */
public final class LongMemory {

    private final ConcurrentMap<String, CopyOnWriteArrayList<MemoryEntry>> sessions =
            new ConcurrentHashMap<>();

    /**
     * 创建一个空的进程内长期记忆存储。
     */
    public LongMemory() {
    }

    /**
     * 向指定会话追加一条长期记忆。
     *
     * @param sessionId 会话标识
     * @param entry 待保存的记忆记录
     * @throws IllegalArgumentException 当会话标识为空时抛出
     * @throws NullPointerException 当记忆记录为 {@code null} 时抛出
     */
    public void append(String sessionId, MemoryEntry entry) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        sessions.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(entry, "entry must not be null"));
    }

    /**
     * 读取指定会话的全部长期记忆。
     *
     * @param sessionId 会话标识
     * @return 按写入顺序排列的只读记忆快照；会话不存在时返回空列表
     */
    public List<MemoryEntry> read(String sessionId) {
        List<MemoryEntry> entries = sessions.get(sessionId);
        return entries == null ? List.of() : List.copyOf(entries);
    }
}
