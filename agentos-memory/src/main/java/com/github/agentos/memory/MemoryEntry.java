package com.github.agentos.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条不可变的 Agent 记忆记录。
 *
 * @param role 记忆内容的来源角色
 * @param content 记忆文本内容
 * @param createdAt 记忆创建时间
 */
public record MemoryEntry(Role role, String content, Instant createdAt) {

    /**
     * 创建并校验记忆记录。
     *
     * @throws NullPointerException 当任一字段为 {@code null} 时抛出
     */
    public MemoryEntry {
        role = Objects.requireNonNull(role, "role must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * 创建一条用户消息记忆。
     *
     * @param content 用户消息内容
     * @return 带有当前时间的用户记忆
     */
    public static MemoryEntry user(String content) {
        return new MemoryEntry(Role.USER, content, Instant.now());
    }

    /**
     * 创建一条 Agent 回复记忆。
     *
     * @param content Agent 回复内容
     * @return 带有当前时间的 Agent 记忆
     */
    public static MemoryEntry assistant(String content) {
        return new MemoryEntry(Role.ASSISTANT, content, Instant.now());
    }

    /**
     * 创建一条系统事实记忆。
     *
     * @param content 系统事实内容
     * @return 带有当前时间的系统记忆
     */
    public static MemoryEntry system(String content) {
        return new MemoryEntry(Role.SYSTEM, content, Instant.now());
    }

    /**
     * 记忆内容的来源角色。
     */
    public enum Role {
        /** 系统信息或长期事实。 */
        SYSTEM,
        /** 用户输入。 */
        USER,
        /** Agent 回复。 */
        ASSISTANT,
        /** 工具执行结果。 */
        TOOL
    }
}
