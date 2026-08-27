package com.github.agentos.agent.routing;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Supervisor 生成的结构化路由决策。 */
public record SupervisorRouteDecision(
        String intent,
        RouteScope scope,
        Set<AgentCapability> requiredCapabilities,
        String targetAgent,
        double confidence,
        String reason,
        String clarifyingQuestion) {

    /** 规范化模型输出并拒绝不完整或矛盾的路由结果。 */
    public SupervisorRouteDecision {
        intent = requireText(intent, "intent");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        requiredCapabilities = requiredCapabilities == null
                ? Set.of() : Set.copyOf(requiredCapabilities);
        targetAgent = requireText(targetAgent, "targetAgent");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0.0, 1.0]");
        }
        reason = requireText(reason, "reason");
        clarifyingQuestion = clarifyingQuestion == null ? "" : clarifyingQuestion.trim();
    }

    /** Jackson 反序列化使用的稳定列表入口。 */
    public SupervisorRouteDecision(
            String intent,
            RouteScope scope,
            List<AgentCapability> requiredCapabilities,
            String targetAgent,
            double confidence,
            String reason,
            String clarifyingQuestion) {
        this(intent, scope,
                requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities),
                targetAgent, confidence, reason, clarifyingQuestion);
    }

    /** 是否携带可直接返回给用户的澄清问题。 */
    public boolean requiresClarification() {
        return !clarifyingQuestion.isBlank();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
