package com.github.agentos.kernel;

/**
 * 单次 Agent 运行所需的身份和任务作用域上下文。
 *
 * <p>用户目标、会话标识和请求属性属于 {@link AgentRequest}，不在上下文中重复保存。</p>
 *
 * @param teamId 团队标识
 * @param userId 用户标识
 * @param agentId Agent 标识
 * @param taskId 可选任务标识
 */
public record AgentContext(
        String teamId,
        String userId,
        String agentId,
        String taskId,
        AgentInvocation invocation) {

    /** 保留既有四参数 API，Invocation 由 Runtime 在执行边界注入。 */
    public AgentContext(String teamId, String userId, String agentId, String taskId) {
        this(teamId, userId, agentId, taskId, null);
    }

    /**
     * 创建并校验 Agent 上下文。
     *
     * @throws IllegalArgumentException 当团队、用户或 Agent 标识为空时抛出
     */
    public AgentContext {
        teamId = requireText(teamId, "teamId");
        userId = requireText(userId, "userId");
        agentId = requireText(agentId, "agentId");
        taskId = taskId == null ? "" : taskId.trim();
    }

    /** 创建默认团队和用户作用域下的 Agent 上下文。 */
    public static AgentContext of(String agentId) {
        return new AgentContext("default-team", "default-user", agentId, "");
    }

    /** 创建完整的身份和任务作用域上下文。 */
    public static AgentContext scoped(
            String teamId,
            String userId,
            String agentId,
            String taskId) {
        return new AgentContext(teamId, userId, agentId, taskId);
    }

    /** 返回绑定指定 Invocation 的新上下文。 */
    public AgentContext withInvocation(AgentInvocation value) {
        return new AgentContext(teamId, userId, agentId, taskId,
                java.util.Objects.requireNonNull(value, "invocation must not be null"));
    }

    /** 返回 Runtime 注入的 Invocation 标识，未进入 Runtime 时返回空字符串。 */
    public String invocationId() {
        return invocation == null ? "" : invocation.invocationId();
    }

    /** 返回 Runtime 注入的 Session 标识，未进入 Runtime 时返回空字符串。 */
    public String sessionId() {
        return invocation == null ? "" : invocation.sessionId();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
