package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话历史处理器单元测试。 */
class HistoryProcessorTest {

    @Test
    void parsesUserAssistantTurnsIntoNativeMessages() {
        assertThat(HistoryProcessor.parseHistory(
                "用户：今天几号\n助手：今天是 2026-08-19 星期三"))
                .containsExactly(
                        LlmMessage.user("今天几号"),
                        LlmMessage.assistant("今天是 2026-08-19 星期三"));
    }

    @Test
    void continuationLinesBelongToPreviousMessage() {
        assertThat(HistoryProcessor.parseHistory(
                "用户：写一首诗\n要有两个段落\n助手：好的\n第一段内容"))
                .containsExactly(
                        LlmMessage.user("写一首诗\n要有两个段落"),
                        LlmMessage.assistant("好的\n第一段内容"));
    }

    @Test
    void blankHistoryLeavesRequestUnchanged() {
        HistoryProcessor processor = new HistoryProcessor();
        AgentRequest request = new AgentRequest("s1", "当前问题", Map.of(
                HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE, "   "));

        LlmRequest processed = processor.process(LlmRequest.of("当前问题"), request);

        assertThat(processed.messages())
                .containsExactly(LlmMessage.user("当前问题"));
    }

    @Test
    void missingAttributeLeavesRequestUnchanged() {
        HistoryProcessor processor = new HistoryProcessor();
        AgentRequest request = new AgentRequest("s1", "当前问题", Map.of());

        LlmRequest processed = processor.process(LlmRequest.of("当前问题"), request);

        assertThat(processed.messages())
                .containsExactly(LlmMessage.user("当前问题"));
    }

    @Test
    void historyIsInsertedBeforeCurrentInput() {
        HistoryProcessor processor = new HistoryProcessor();
        AgentRequest request = new AgentRequest("s1", "那明天呢", Map.of(
                HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE,
                "用户：今天几号\n助手：2026-08-19"));

        LlmRequest processed = processor.process(LlmRequest.of("那明天呢"), request);

        assertThat(processed.messages()).containsExactly(
                LlmMessage.user("今天几号"),
                LlmMessage.assistant("2026-08-19"),
                LlmMessage.user("那明天呢"));
    }

    @Test
    void parsesReasoningAnnotationOnAssistantLine() {
        String reasoning = "先回忆用户问的是日期，然后查日历。";
        String encoded = Base64.getEncoder().encodeToString(
                reasoning.getBytes(StandardCharsets.UTF_8));
        String history = "用户：今天几号\n助手：[reasoning=" + encoded + "]2026-08-19";

        assertThat(HistoryProcessor.parseHistory(history))
                .containsExactly(
                        LlmMessage.user("今天几号"),
                        LlmMessage.assistantWithReasoning("2026-08-19", reasoning));
    }

    @Test
    void malformedReasoningAnnotationFallsBackToPlainAssistant() {
        String history = "用户：今天几号\n助手：[reasoning=!!not-base64!!]2026-08-19";

        assertThat(HistoryProcessor.parseHistory(history))
                .containsExactly(
                        LlmMessage.user("今天几号"),
                        LlmMessage.assistant("2026-08-19"));
    }

    @Test
    void reasoningAnnotationOnUserLineIsIgnored() {
        // 推理标注只对助手消息生效；用户消息上的同名标记按正文保留。
        String history = "用户：[reasoning=abc]那明天呢\n助手：明天 2026-08-20";

        assertThat(HistoryProcessor.parseHistory(history))
                .containsExactly(
                        LlmMessage.user("[reasoning=abc]那明天呢"),
                        LlmMessage.assistant("明天 2026-08-20"));
    }
}
