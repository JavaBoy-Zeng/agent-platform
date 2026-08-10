package com.github.agentos.memory;

import java.util.Objects;

/**
 * 一次记忆读写的强类型隔离作用域。
 *
 * @param teamId 团队标识
 * @param userId 用户标识
 * @param agentId Agent 标识
 * @param sessionId 会话标识
 * @param taskId 可选任务标识，空字符串表示未绑定任务
 */
public record MemoryScope(
        String teamId,
        String userId,
        String agentId,
        String sessionId,
        String taskId) {

    public static final String DEFAULT_TEAM_ID = "default-team";
    public static final String DEFAULT_USER_ID = "default-user";

    public MemoryScope {
        teamId = requireText(teamId, "teamId");
        userId = requireText(userId, "userId");
        agentId = requireText(agentId, "agentId");
        sessionId = requireText(sessionId, "sessionId");
        taskId = Objects.requireNonNullElse(taskId, "").trim();
    }

    /** 创建兼容单用户运行模式的默认作用域。 */
    public static MemoryScope defaultScope(String agentId, String sessionId) {
        return new MemoryScope(DEFAULT_TEAM_ID, DEFAULT_USER_ID, agentId, sessionId, "");
    }

    /** 当前记录是否属于同一完整会话作用域。 */
    public boolean sameConversation(MemoryScope other) {
        return sameActor(other) && sessionId.equals(other.sessionId)
                && (taskId.isEmpty() || other.taskId.isEmpty() || taskId.equals(other.taskId));
    }

    /** 当前记录是否属于同一用户和 Agent，可跨会话召回。 */
    public boolean sameActor(MemoryScope other) {
        return other != null
                && teamId.equals(other.teamId)
                && userId.equals(other.userId)
                && agentId.equals(other.agentId);
    }

    /** 当前记录是否属于同一场景聚合范围。 */
    public boolean sameScenario(MemoryScope other) {
        return sameActor(other)
                && (taskId.isEmpty() || other.taskId.isEmpty() || taskId.equals(other.taskId));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
