package com.github.agentos.agent.loop;

import com.github.agentos.kernel.AgentContext;
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
                AgentContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(chatCalls.get()).isEqualTo(1);
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED, AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events.get(1).message()).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(events.get(1).data()).containsEntry("router", "direct-chat");
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
                AgentContext.of("main-agent"),
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
                AgentContext.of("main-agent"),
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
}
