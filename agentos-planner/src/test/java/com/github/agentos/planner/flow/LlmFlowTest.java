package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LlmFlow 处理器链单元测试。 */
class LlmFlowTest {

    @Test
    void appliesProcessorsInDeclaredOrder() {
        List<String> order = new java.util.ArrayList<>();
        LlmFlow flow = new LlmFlow(List.of(
                (request, agentRequest) -> {
                    order.add("instruction");
                    return request.withSystemInstruction("指令");
                },
                (request, agentRequest) -> {
                    order.add("history");
                    return request.prependHistory(List.of(LlmMessage.user("历史问题")));
                }));

        LlmRequest request = flow.build(
                new AgentRequest("s1", "当前问题", Map.of()));

        assertThat(order).containsExactly("instruction", "history");
        assertThat(request.instruction()).contains("指令");
        assertThat(request.messages()).containsExactly(
                LlmMessage.user("历史问题"),
                LlmMessage.user("当前问题"));
    }

    @Test
    void emptyProcessorChainKeepsPlainObjective() {
        LlmFlow flow = new LlmFlow(List.of());

        LlmRequest request = flow.build(
                new AgentRequest("s1", "什么是 JVM", Map.of()));

        assertThat(request.instruction()).isEmpty();
        assertThat(request.messages())
                .containsExactly(LlmMessage.user("什么是 JVM"));
    }
}
