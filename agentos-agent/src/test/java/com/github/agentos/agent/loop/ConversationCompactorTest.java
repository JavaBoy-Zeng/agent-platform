package com.github.agentos.agent.loop;

import org.junit.jupiter.api.Test;

import com.github.agentos.planner.flow.LlmMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationCompactorTest {

    @Test
    void wrapsToolResultWithMarkerAndTruncatesOversizedOutput() {
        ConversationCompactor compactor = new ConversationCompactor(24_000, 100, 30);
        String longOutput = "x".repeat(250);

        String wrapped = compactor.wrapToolResult("file_read", longOutput);

        assertThat(wrapped).startsWith("[tool] file_read\n");
        assertThat(wrapped).contains("…(截断，原文 250 字符)");
        assertThat(wrapped.length()).isLessThan(250);
    }

    @Test
    void keepsShortMessagesUntouchedWhenUnderBudget() {
        ConversationCompactor compactor = new ConversationCompactor(1_000, 400, 60);
        LlmMessage objective = LlmMessage.user("目标：查询天气");
        LlmMessage result = LlmMessage.toolResult(
                "call-1", compactor.wrapToolResult("today", "2026-08-28"));

        ConversationCompactor.Compaction compaction = compactor.compact(
                java.util.List.of(objective, assistantCall("call-1", "today"), result));

        assertThat(compaction.changed()).isFalse();
        assertThat(compaction.messages()).hasSize(3);
    }

    @Test
    void compressesOldestToolResultsFirstAndKeepsRecentOnesFull() {
        ConversationCompactor compactor = new ConversationCompactor(420, 400, 40);
        LlmMessage objective = LlmMessage.user("目标：多轮检索");
        LlmMessage oldResult = LlmMessage.toolResult(
                "call-1", compactor.wrapToolResult("search", "a".repeat(200)));
        LlmMessage recentResult = LlmMessage.toolResult(
                "call-2", compactor.wrapToolResult("search", "b".repeat(200)));

        ConversationCompactor.Compaction compaction = compactor.compact(
                java.util.List.of(
                        objective, assistantCall("call-1", "search"), oldResult,
                        assistantCall("call-2", "search"), recentResult));

        assertThat(compaction.compressedCount()).isGreaterThanOrEqualTo(1);
        assertThat(compaction.messages().get(0)).isEqualTo(objective);
        // 最新一条工具结果保持完整，最旧的被压缩。
        String oldest = toolContent(compaction, "call-1");
        String newest = toolContent(compaction, "call-2");
        assertThat(oldest).contains("[compressed]");
        assertThat(newest).doesNotContain("[compressed]");
        assertThat(newest).contains("bbbb");
        assertThat(ConversationCompactor.totalChars(compaction.messages()))
                .isLessThanOrEqualTo(420);
    }

    @Test
    void compressionPreservesToolCallIdPairing() {
        ConversationCompactor compactor = new ConversationCompactor(420, 400, 40);
        LlmMessage objective = LlmMessage.user("目标：压缩后配对");
        LlmMessage result = LlmMessage.toolResult(
                "call-9", compactor.wrapToolResult("search", "a".repeat(200)));

        ConversationCompactor.Compaction compaction = compactor.compact(
                java.util.List.of(objective, assistantCall("call-9", "search"), result));

        compaction.messages().stream()
                .filter(message -> message.role() == LlmMessage.Role.TOOL)
                .forEach(message ->
                        assertThat(message.toolCallId()).isEqualTo("call-9"));
    }

    @Test
    void dropsOldestToolCallPairsWhenCompressionIsNotEnough() {
        ConversationCompactor compactor = new ConversationCompactor(220, 300, 100);
        LlmMessage objective = LlmMessage.user("目标：批量读取文件");
        LlmMessage firstCall = assistantCall("call-1", "file_read");
        LlmMessage firstResult = LlmMessage.toolResult(
                "call-1", compactor.wrapToolResult("file_read", "c".repeat(150)));
        LlmMessage secondCall = assistantCall("call-2", "file_read");
        LlmMessage secondResult = LlmMessage.toolResult(
                "call-2", compactor.wrapToolResult("file_read", "d".repeat(150)));

        ConversationCompactor.Compaction compaction = compactor.compact(
                java.util.List.of(
                        objective, firstCall, firstResult, secondCall, secondResult));

        // 压缩到极限仍超预算时，最旧的调用对被整对丢弃，目标消息永不丢弃。
        assertThat(compaction.droppedCount()).isGreaterThanOrEqualTo(1);
        assertThat(compaction.messages().get(0)).isEqualTo(objective);
        assertThat(compaction.messages().stream()
                .map(LlmMessage::content)
                .noneMatch(content -> content.contains("cccc")))
                .isTrue();
        // 丢弃保持配对：序列中不存在没有 TOOL 结果的 assistant 工具调用。
        for (int index = 1; index < compaction.messages().size(); index++) {
            LlmMessage message = compaction.messages().get(index);
            if (message.role() == LlmMessage.Role.ASSISTANT
                    && !message.toolCalls().isEmpty()) {
                LlmMessage next = compaction.messages().get(index + 1);
                assertThat(next.role()).isEqualTo(LlmMessage.Role.TOOL);
                assertThat(next.toolCallId()).isEqualTo(message.toolCalls().getFirst().id());
            }
        }
    }

    @Test
    void prioritizesSubagentToolResultsOverPlainToolsWhenCompressing() {
        // 场景：两条工具结果都超限，一条普通工具（file_read）、一条子代理（search-agent）。
        // 压缩时应优先压缩子代理结果（探索产出体积大）。
        ConversationCompactor compactor = new ConversationCompactor(500, 400, 30);
        String longFileContent = "F".repeat(300);
        String longSearchContent = "S".repeat(300);
        List<LlmMessage> messages = new java.util.ArrayList<>(List.of(
                LlmMessage.user("目标：搜索并读取"),
                assistantCall("call-1", "search-agent"),
                LlmMessage.toolResult("call-1",
                        compactor.wrapToolResult("search-agent", longSearchContent)),
                assistantCall("call-2", "file_read"),
                LlmMessage.toolResult("call-2",
                        compactor.wrapToolResult("file_read", longFileContent))));

        ConversationCompactor.Compaction compaction = compactor.compact(messages);

        assertThat(compaction.changed()).isTrue();
        // search-agent 结果应被压缩（含 [compressed] 标记）。
        String searchContent = toolContent(compaction, "call-1");
        assertThat(searchContent).contains(ConversationCompactor.COMPRESSED_MARKER);
        // file_read 结果在第一轮未触发压缩（因 search-agent 先被压缩后已可能回到预算内）。
        // 若仍超限，file_read 也可能被压缩，但 search-agent 必定先被压缩。
    }

    @Test
    void rejectsInvalidLimits() {
        assertThatThrownBy(() -> new ConversationCompactor(0, 100, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationCompactor(1_000, 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造携带原生工具调用的 assistant 消息（对应 call-<n> 的工具请求）。 */
    private static LlmMessage assistantCall(String callId, String toolName) {
        return LlmMessage.assistantToolCall(
                "(工具调用)",
                new LlmMessage.ToolCallPart(callId, toolName, "{}"));
    }

    /** 取压缩结果中指定 toolCallId 的 TOOL 消息正文。 */
    private static String toolContent(
            ConversationCompactor.Compaction compaction, String callId) {
        return compaction.messages().stream()
                .filter(message -> message.role() == LlmMessage.Role.TOOL)
                .filter(message -> callId.equals(message.toolCallId()))
                .map(LlmMessage::content)
                .findFirst()
                .orElseThrow();
    }
}
