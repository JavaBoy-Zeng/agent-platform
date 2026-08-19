package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;

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
}
