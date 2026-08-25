package com.github.agentos.agent.loop;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
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
        ChatClient chatClient = (sessionId, request) -> {
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
    void passesObjectiveAndInstructionToChatClient() {
        List<LlmRequest> requests = new ArrayList<>();
        ChatClient chatClient = (sessionId, request) -> {
            requests.add(request);
            return "ok";
        };
        SimpleQaAgent agent = new SimpleQaAgent(chatClient);

        agent.run(
                new AgentRequest("session-9", "1+1 等于几", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.messages())
                    .containsExactly(LlmMessage.user("1+1 等于几"));
            assertThat(request.instruction()).isPresent();
            assertThat(request.instruction().orElseThrow()).contains("中文助手");
        });
    }

    /**
     * 直答通道没有工具，但它仍是本机 Agent 平台的一部分。指令若只说“你无法获取”，
     * 模型会自称云端服务并教用户手工操作；必须让它把限制归因于本轮通道并引导重试。
     */
    @Test
    void instructionDescribesServiceHostWithoutAssumingDeploymentTopology() {
        List<LlmRequest> requests = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, request) -> {
            requests.add(request);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "什么是 JVM", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        String instruction = requests.getFirst().instruction().orElseThrow();
        assertThat(instruction)
                .contains("运行 AgentOS")
                .contains("可能是用户本机，也可能是远程服务器")
                .contains("本轮无法调用");
    }

    /**
     * 直答通道曾在用户说过“我叫曾智”的下一轮回答“每次对话都是独立的”，
     * 并把“曾智”截成“智”。指令必须要求它使用历史里的稳定事实。
     */
    @Test
    void instructionRequiresUsingConversationHistoryFacts() {
        List<LlmRequest> requests = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, request) -> {
            requests.add(request);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "我是谁", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(requests.getFirst().instruction().orElseThrow())
                .contains("本会话真实")
                .contains("不要重复询问")
                .contains("不要截取其中一个字当作称呼")
                .contains("禁止声称“每次对话都是独立的”");
    }

    @Test
    void failsWithTerminalStateWhenChatClientErrors() {
        List<AgentRunEvent> events = new ArrayList<>();
        ChatClient chatClient = (sessionId, request) -> {
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
        assertThat(new SimpleQaAgent((s, r) -> "x").id()).isEqualTo("simple-qa-agent");
    }

    @Test
    void streamsDeltasAndEmitsUsageWhenClientSupportsIt() {
        List<AgentRunEvent> events = new ArrayList<>();
        ChatClient streamingClient = new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                return "Java 是一门语言。";
            }

            @Override
            public ChatResponse chatStream(
                    String sessionId, LlmRequest request,
                    java.util.function.Consumer<String> onDelta) {
                onDelta.accept("Java ");
                onDelta.accept("是一门语言。");
                return new ChatResponse(
                        "Java 是一门语言。",
                        new com.github.agentos.kernel.ModelUsage("test-model", 12, 8));
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
        SimpleQaAgent agent = new SimpleQaAgent((s, r) -> "ok");

        agent.run(
                new AgentRequest("s1", "hi", Map.of()),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(events).extracting(AgentRunEvent::type)
                .doesNotContain(AgentRunEvent.Type.USAGE);
    }

    @Test
    void expandsConversationHistoryIntoNativeMessages() {
        List<LlmRequest> requests = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, request) -> {
            requests.add(request);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "那明天呢", Map.of(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE,
                        "用户：今天几号\n助手：今天是 2026-08-19 星期三")),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        // 历史展开为真正的 user/assistant 消息，当前输入保持在末尾。
        assertThat(requests).singleElement().satisfies(request ->
                assertThat(request.messages()).containsExactly(
                        LlmMessage.user("今天几号"),
                        LlmMessage.assistant("今天是 2026-08-19 星期三"),
                        LlmMessage.user("那明天呢")));
    }

    @Test
    void keepsPlainObjectiveWithoutHistoryAttribute() {
        List<LlmRequest> requests = new ArrayList<>();
        SimpleQaAgent agent = new SimpleQaAgent((s, request) -> {
            requests.add(request);
            return "ok";
        });

        agent.run(
                new AgentRequest("s1", "1+1 等于几", Map.of(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE, "  ")),
                InvocationContext.of("main-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(requests).singleElement()
                .satisfies(request -> assertThat(request.messages())
                        .containsExactly(LlmMessage.user("1+1 等于几")));
    }
}
