package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 首版执行模式规则测试。 */
class RuleBasedExecutionPolicyTest {

    private final RuleBasedExecutionPolicy policy = new RuleBasedExecutionPolicy();
    private final InvocationContext context = InvocationContext.of("main-agent");

    @Test
    void selectsDirectForPlainConversation() {
        assertThat(policy.select(AgentRequest.of("s1", "解释一下什么是 JVM"), context))
                .isEqualTo(ExecutionMode.DIRECT);
    }

    @Test
    void selectsReactForSingleToolRequest() {
        AgentRequest request = new AgentRequest(
                "s1", "获取数据", Map.of("requiresTool", true, "estimatedToolCalls", 1));
        assertThat(policy.select(request, context)).isEqualTo(ExecutionMode.REACT);
    }

    @Test
    void selectsPlanForComplexOrRiskyWork() {
        assertThat(policy.select(
                AgentRequest.of("s1", "重构这个项目并完成部署"), context))
                .isEqualTo(ExecutionMode.PLAN);
        assertThat(policy.select(
                new AgentRequest("s2", "do work", Map.of("risky", true)), context))
                .isEqualTo(ExecutionMode.PLAN);
    }
}
