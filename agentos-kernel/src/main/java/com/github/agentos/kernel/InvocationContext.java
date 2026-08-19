package com.github.agentos.kernel;

import java.util.Objects;

/**
 * 一次 Agent Invocation 的运行环境。
 *
 * <p>Runner 在执行边界负责组装该上下文：绑定 {@link Session} 快照、执行预算
 * {@link AgentExecutionLimits}、{@link AgentInvocation} 与领域事件发布器；
 * 执行链（策略、规划器、工具）统一从这里读取运行世界，而不是各自接收散参。</p>
 *
 * <p>用户目标、会话标识和请求属性属于 {@link AgentRequest}，不在上下文中重复保存。</p>
 *
 * @param teamId 团队标识
 * @param userId 用户标识
 * @param agentId Agent 标识
 * @param taskId 可选任务标识
 * @param session 本次运行的会话快照；Runner 注入前为 {@code null}
 * @param budget 本次运行的累计资源预算
 * @param invocation 本次运行记录；Runner 注入前为 {@code null}
 * @param eventPublisher 领域事件发布器
 */
public record InvocationContext(
        String teamId,
        String userId,
        String agentId,
        String taskId,
        Session session,
        AgentExecutionLimits budget,
        AgentInvocation invocation,
        AgentEventPublisher eventPublisher) {

    /** 调用方仅提供身份作用域，会话、预算与 Invocation 由 Runner 在执行边界注入。 */
    public InvocationContext(String teamId, String userId, String agentId, String taskId) {
        this(teamId, userId, agentId, taskId, null, null, null, AgentEventPublisher.NOOP);
    }

    /**
     * 创建并校验 Invocation 上下文。
     *
     * @throws IllegalArgumentException 当团队、用户或 Agent 标识为空时抛出
     */
    public InvocationContext {
        teamId = requireText(teamId, "teamId");
        userId = requireText(userId, "userId");
        agentId = requireText(agentId, "agentId");
        taskId = taskId == null ? "" : taskId.trim();
        budget = budget == null ? AgentExecutionLimits.defaults() : budget;
        eventPublisher = eventPublisher == null ? AgentEventPublisher.NOOP : eventPublisher;
    }

    /** 创建默认团队和用户作用域下的 Invocation 上下文。 */
    public static InvocationContext of(String agentId) {
        return new InvocationContext("default-team", "default-user", agentId, "");
    }

    /** 创建完整的身份和任务作用域上下文。 */
    public static InvocationContext scoped(
            String teamId,
            String userId,
            String agentId,
            String taskId) {
        return new InvocationContext(teamId, userId, agentId, taskId);
    }

    /** 返回绑定指定会话快照的新上下文。 */
    public InvocationContext withSession(Session value) {
        return new InvocationContext(teamId, userId, agentId, taskId,
                Objects.requireNonNull(value, "session must not be null"),
                budget, invocation, eventPublisher);
    }

    /** 返回切换执行 Agent 标识后的新上下文，供 Workflow Agent 派生子 Agent 作用域。 */
    public InvocationContext withAgentId(String value) {
        return new InvocationContext(teamId, userId,
                Objects.requireNonNull(
                        requireText(value, "agentId"), "agentId must not be null"),
                taskId, session, budget, invocation, eventPublisher);
    }

    /** 返回替换执行预算后的新上下文。 */
    public InvocationContext withBudget(AgentExecutionLimits value) {
        return new InvocationContext(teamId, userId, agentId, taskId,
                session,
                Objects.requireNonNull(value, "budget must not be null"),
                invocation, eventPublisher);
    }

    /** 返回同时绑定 Invocation 与 Runner 领域事件发布器的新上下文。 */
    public InvocationContext withRuntime(
            AgentInvocation value, AgentEventPublisher publisher) {
        return new InvocationContext(teamId, userId, agentId, taskId,
                session, budget,
                Objects.requireNonNull(value, "invocation must not be null"),
                Objects.requireNonNull(publisher, "publisher must not be null"));
    }

    /** 返回绑定指定 Invocation 的新上下文。 */
    public InvocationContext withInvocation(AgentInvocation value) {
        return new InvocationContext(teamId, userId, agentId, taskId,
                session, budget,
                Objects.requireNonNull(value, "invocation must not be null"),
                eventPublisher);
    }

    /** 返回 Runner 注入的 Invocation 标识，未进入 Runner 时返回空字符串。 */
    public String invocationId() {
        return invocation == null ? "" : invocation.invocationId();
    }

    /** 返回 Runner 注入的 Session 标识，未进入 Runner 时返回空字符串。 */
    public String sessionId() {
        return invocation == null ? "" : invocation.sessionId();
    }

    /** 返回本次运行的结构化会话状态；会话未注入时返回空状态。 */
    public SessionState sessionState() {
        return session == null ? SessionState.empty() : session.state();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
