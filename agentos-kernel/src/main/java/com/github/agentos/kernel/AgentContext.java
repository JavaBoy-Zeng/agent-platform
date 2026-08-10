package com.github.agentos.kernel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 Agent 运行所需的不可变上下文。
 *
 * <p>上下文负责携带 Agent 标识、会话标识、用户输入以及调用方提供的扩展属性。
 * 构造时会复制属性集合，避免运行期间被外部代码修改。</p>
 *
 * @param agentId Agent 的唯一标识
 * @param sessionId 会话的唯一标识，用于关联状态和记忆
 * @param input 本次运行接收到的用户输入
 * @param attributes 调用方提供的只读扩展属性
 */
public record AgentContext(
        String teamId,
        String userId,
        String agentId,
        String sessionId,
        String taskId,
        String input,
        Map<String, Object> attributes) {

    /**
     * 创建并校验 Agent 上下文。
     *
     * @throws IllegalArgumentException 当 Agent 标识或会话标识为空时抛出
     * @throws NullPointerException 当输入或扩展属性为 {@code null} 时抛出
     */
    public AgentContext {
        teamId = requireText(teamId, "teamId");
        userId = requireText(userId, "userId");
        agentId = requireText(agentId, "agentId");
        sessionId = requireText(sessionId, "sessionId");
        taskId = taskId == null ? "" : taskId.trim();
        input = Objects.requireNonNull(input, "input must not be null");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    }

    /** Backward-compatible constructor for callers that do not provide tenancy scope. */
    public AgentContext(String agentId, String sessionId, String input, Map<String, Object> attributes) {
        this("default-team", "default-user", agentId, sessionId, "", input, attributes);
    }

    /**
     * 创建不包含扩展属性的 Agent 上下文。
     *
     * @param agentId Agent 的唯一标识
     * @param sessionId 会话的唯一标识
     * @param input 用户输入
     * @return 新的不可变 Agent 上下文
     */
    public static AgentContext of(String agentId, String sessionId, String input) {
        return new AgentContext(agentId, sessionId, input, Map.of());
    }

    /** Creates a context with the complete memory isolation scope. */
    public static AgentContext scoped(
            String teamId,
            String userId,
            String agentId,
            String sessionId,
            String taskId,
            String input) {
        return new AgentContext(teamId, userId, agentId, sessionId, taskId, input, Map.of());
    }

    /**
     * 在当前上下文基础上增加或替换一项扩展属性。
     *
     * @param name 属性名称
     * @param value 属性值
     * @return 包含新属性的上下文副本
     * @throws IllegalArgumentException 当属性名称为空时抛出
     * @throws NullPointerException 当属性值为 {@code null} 时抛出
     */
    public AgentContext withAttribute(String name, Object value) {
        Map<String, Object> updated = new HashMap<>(attributes);
        updated.put(requireText(name, "attribute name"), Objects.requireNonNull(value, "attribute value"));
        return new AgentContext(teamId, userId, agentId, sessionId, taskId, input, updated);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
