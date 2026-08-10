package com.github.agentos.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 有容量限制的进程内短期记忆。
 *
 * <p>短期记忆按会话维护双端队列，并对单个会话的读写进行同步。
 * 当记录数量超过容量时，最早写入的记录会被自动淘汰。</p>
 */
public final class ShortMemory {

    private final int capacityPerSession;
    private final ConcurrentMap<String, Deque<MemoryEntry>> sessions = new ConcurrentHashMap<>();

    /**
     * 创建短期记忆存储。
     *
     * @param capacityPerSession 每个会话最多保留的记录数
     * @throws IllegalArgumentException 当容量小于或等于零时抛出
     */
    public ShortMemory(int capacityPerSession) {
        if (capacityPerSession <= 0) {
            throw new IllegalArgumentException("capacityPerSession must be positive");
        }
        this.capacityPerSession = capacityPerSession;
    }

    /**
     * 向指定会话追加一条记忆。
     *
     * @param sessionId 会话标识
     * @param entry 待保存的记忆记录
     * @throws IllegalArgumentException 当会话标识为空时抛出
     * @throws NullPointerException 当记忆记录为 {@code null} 时抛出
     */
    public void append(String sessionId, MemoryEntry entry) {
        requireSessionId(sessionId);
        Objects.requireNonNull(entry, "entry must not be null");
        Deque<MemoryEntry> entries = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacityPerSession) {
                entries.removeFirst();
            }
        }
    }

    /**
     * 读取指定会话当前保留的短期记忆。
     *
     * @param sessionId 会话标识
     * @return 按写入顺序排列的只读记忆快照
     * @throws IllegalArgumentException 当会话标识为空时抛出
     */
    public List<MemoryEntry> read(String sessionId) {
        requireSessionId(sessionId);
        Deque<MemoryEntry> entries = sessions.get(sessionId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(new ArrayList<>(entries));
        }
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
