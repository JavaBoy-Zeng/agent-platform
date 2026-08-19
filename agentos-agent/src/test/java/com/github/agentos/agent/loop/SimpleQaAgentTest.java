package com.github.agentos.agent.loop;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.ChatClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 简单问答 Agent 单元测试。 */
class SimpleQaAgentTest {

    @Test
    void answersWithSingleChatCallAndEmitsEvents() {
        AtomicInteger chatCalls = new AtomicInteger();
        List<AgentRunEvent> events = new ArrayList<>();
        ChatClient chatClient = (sessionId, message) -> {
            chatCalls.incrementAndGet();
            return "JVM 是 Java 虚拟机。";
        };
        SimpleQaAgent agent = new SimpleQaAgent(chatClient);

        AgentState result = agent.run(
                new AgentRequest("s1", "什么是 JVM", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(chatCalls.get()).isEqualTo(1);
        // 非流式客户端走默认 chatStream：单次整段 OUTPUT_DELTA。
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events.get(2).message()).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(events.get(2).data()).containsEntry("router", "direct-chat");
    }

    @Test
    void passesObjectiveToChatClient() {
        List<String> messages = new ArrayList<>();
        ChatClient chatClient = (sessionId, message) -> {
            messages.add(sessionId + ":" + message);
            return "ok";
        };
        SimpleQaAgent agent = new SimpleQaAgent(chatClient);

        agent.run(
                new AgentRequest("session-9", "1+1 等于几", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(messages).containsExactly("session-9:1+1 等于几");
    }

    @Test
    void failsWithTerminalStateWhenChatClientErrors() {
        List<AgentRunEvent> events = new ArrayList<>();
        ChatClient chatClient = (sessionId, message) -> {
            throw new IllegalStateException("endpoint unavailable");
        };
        SimpleQaAgent agent = new SimpleQaAgent(chatClient);

        AgentState result = agent.run(
                new AgentRequest("s1", "什么是 JVM", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("endpoint unavailable");
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED, AgentRunEvent.Type.RUN_FAILED);
    }

    @Test
    void exposesStableIdForRouting() {
        assertThat(new SimpleQaAgent((s, m) -> "x").id()).isEqualTo("simple-qa-agent");
    }

    @Test
    void streamsDeltasAndEmitsUsageWhenClientSupportsIt() {
        List<AgentRunEvent> events = new ArrayList<>();
        ChatClient streamingClient = new ChatClient() {
            @Override
            public String chat(String sessionId, String userMessage) {
                return "Java 是一门语言。";
            }

            @Override
            public ChatResponse chatStream(
                    String sessionId, String userMessage,
                    java.util.function.Consumer<String> onDelta) {
                onDelta.accept("Java ");
                onDelta.accept("是一门语言。");
                return new ChatResponse(
                        "Java 是一门语言。",
                        new com.github.agentos.planner.ModelUsage("test-model", 12, 8));
            }
        };
        SimpleQaAgent agent = new SimpleQaAgent(streamingClient);

        AgentState result = agent.run(
                new AgentRequest("s1", "什么是 Java", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.USAGE,
                AgentRunEvent.Type.RUN_COMPLETED);
        AgentRunEvent usageEvent = events.get(3);
        assertThat(usageEvent.data())
                .containsEntry("totalTokens", 20L)
                .containsEntry("model", "test-model");
        List<AgentRunEvent> deltas = events.stream()
                .filter(event -> event.type() == AgentRunEvent.Type.OUTPUT_DELTA).toList();
        assertThat(deltas).extracting(AgentRunEvent::message)
                .containsExactly("Java ", "是一门语言。");
        assertThat(deltas.get(0).data()).containsEntry("sequence", 1);
        assertThat(deltas.get(1).data()).containsEntry("sequence", 2);
    }

    @Test
    void omitsUsageEventWhenClientDoesNotReportIt() {
        List<AgentRunEvent> events = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, m) -> "ok");

        agent.run(
                new AgentRequest("s1", "hi", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(events).extracting(AgentRunEvent::type)
                .doesNotContain(AgentRunEvent.Type.USAGE);
    }

    @Test
    void prependsConversationHistoryWhenAttributePresent() {
        List<String> prompts = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, m) -> {
            prompts.add(m);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "那明天呢", Map.of(
                        SimpleQaAgent.CONVERSATION_HISTORY_ATTRIBUTE,
                        "用户：今天几号\n助手：今天是 2026-08-19 星期三")),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("用户：今天几号");
            assertThat(prompt).contains("助手：今天是 2026-08-19 星期三");
            assertThat(prompt).endsWith("当前问题：那明天呢");
        });
    }

    @Test
    void keepsPlainObjectiveWithoutHistoryAttribute() {
        List<String> prompts = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, m) -> {
            prompts.add(m);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "1+1 等于几", Map.of(
                        SimpleQaAgent.CONVERSATION_HISTORY_ATTRIBUTE, "  ")),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(prompts).containsExactly("1+1 等于几");
    }
}
