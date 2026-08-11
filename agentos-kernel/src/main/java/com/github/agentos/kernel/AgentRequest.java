package com.github.agentos.kernel;

import java.util.Map;

/**
 * 一次 Agent 运行需要完成的用户请求。
 *
 * @param sessionId 会话标识
 * @param objective 用户希望 Agent 完成的目标
 * @param attributes 请求级扩展属性
 */
public record AgentRequest(
        String sessionId,
        String objective,
        Map<String, Object> attributes) {

    /** 创建并校验 Agent 请求。 */
    public AgentRequest {
        sessionId = requireText(sessionId, "sessionId");
        objective = requireText(objective, "objective");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 创建不包含扩展属性的 Agent 请求。 */
    public static AgentRequest of(String sessionId, String objective) {
        return new AgentRequest(sessionId, objective, Map.of());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
