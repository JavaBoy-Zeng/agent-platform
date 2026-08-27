package com.github.agentos.agent.finalize;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.PlanOrigin;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;
import com.github.agentos.planner.flow.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStreamingAgentFinalizerTest {

    @Test
    void buildsFinalRequestAndPreservesProviderDeltaBoundaries() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        ChatClient client = streamingClient(captured, List.of("第一段", "第二段"));
        ModelStreamingAgentFinalizer finalizer = new ModelStreamingAgentFinalizer(client);
        List<String> deltas = new ArrayList<>();

        String answer = finalizer.finishStreaming(
                AgentRequest.of("s1", "分析测试结果"),
                InvocationContext.of("main-agent"),
                completePlan("测试通过 20 项"),
                deltas::add);

        assertThat(answer).isEqualTo("第一段第二段");
        assertThat(deltas).containsExactly("第一段", "第二段");
        assertThat(captured.get().instruction()).hasValueSatisfying(instruction ->
                assertThat(instruction).contains("最终回答生成器", "禁止猜测"));
        assertThat(captured.get().messages().getFirst().content())
                .contains("分析测试结果", "测试通过 20 项");
        assertThat(finalizer.requiresModelCall()).isTrue();
    }

    @Test
    void truncatesDuringStreamingAndEmitsTheMarkerAsTheLastDelta() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        ChatClient client = streamingClient(captured, List.of("A".repeat(40), "B".repeat(40)));
        ModelStreamingAgentFinalizer finalizer = new ModelStreamingAgentFinalizer(client, 64, 100);
        List<String> deltas = new ArrayList<>();

        String answer = finalizer.finishStreaming(
                AgentRequest.of("s1", "生成长回答"),
                InvocationContext.of("main-agent"),
                completePlan("候选"),
                deltas::add);

        assertThat(answer).hasSize(64).endsWith("[final answer truncated by runtime]");
        assertThat(String.join("", deltas)).isEqualTo(answer);
        assertThat(deltas.getLast()).isEqualTo("\n[final answer truncated by runtime]");
    }

    private static AgentPlan completePlan(String draft) {
        return AgentPlan.create(
                PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                "final answer", List.of(), draft);
    }

    private static ChatClient streamingClient(
            AtomicReference<LlmRequest> captured, List<String> chunks) {
        return new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                throw new AssertionError("non-streaming chat must not be used");
            }

            @Override
            public ChatResponse chatStream(
                    String sessionId, LlmRequest request, Consumer<String> onDelta) {
                captured.set(request);
                StringBuilder answer = new StringBuilder();
                chunks.forEach(chunk -> {
                    answer.append(chunk);
                    onDelta.accept(chunk);
                });
                return new ChatResponse(answer.toString(), null);
            }
        };
    }
}
