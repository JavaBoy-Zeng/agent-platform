package com.github.agentos.server.history;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.planner.flow.HistoryProcessor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话多轮历史服务单元测试。 */
class SessionHistoryServiceTest {

    private final InMemoryAgentEventStore store = new InMemoryAgentEventStore();
    private final SessionHistoryService service = new SessionHistoryService(store, 5, 400);

    @Test
    void pairsCompletedTurnsInChronologicalOrder() {
        append("i1", AgentEventType.AGENT_STARTED, "今天几号", 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "今天是 2026-08-19", 2);
        append("i2", AgentEventType.AGENT_STARTED, "那明天呢", 3);
        append("i2", AgentEventType.AGENT_COMPLETED, "明天是 2026-08-20", 4);

        Optional<String> history = service.history("session-1");

        assertThat(history).hasValueSatisfying(text -> assertThat(text)
                .isEqualTo("用户：今天几号\n助手：今天是 2026-08-19\n用户：那明天呢\n助手：明天是 2026-08-20"));
    }

    @Test
    void skipsIncompleteAndFailedTurns() {
        append("i1", AgentEventType.AGENT_STARTED, "第一个问题", 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "第一个回答", 2);
        append("i2", AgentEventType.AGENT_STARTED, "失败的问题", 3);
        append("i2", AgentEventType.AGENT_FAILED, "endpoint unavailable", 4);
        append("i3", AgentEventType.AGENT_STARTED, "等待审批的问题", 5);

        Optional<String> history = service.history("session-1");

        assertThat(history).hasValue("用户：第一个问题\n助手：第一个回答");
    }

    @Test
    void returnsEmptyWhenSessionHasNoCompletedTurn() {
        append("i1", AgentEventType.AGENT_STARTED, "只有开始", 1);

        assertThat(service.history("session-1")).isEmpty();
        assertThat(service.history("unknown-session")).isEmpty();
    }

    @Test
    void keepsOnlyMostRecentTurns() {
        SessionHistoryService limited = new SessionHistoryService(store, 2, 400);
        for (int index = 1; index <= 3; index++) {
            append("i" + index, AgentEventType.AGENT_STARTED, "问题" + index, index * 10);
            append("i" + index, AgentEventType.AGENT_COMPLETED, "回答" + index, index * 10 + 1);
        }

        Optional<String> history = limited.history("session-1");

        assertThat(history).hasValueSatisfying(text -> assertThat(text)
                .isEqualTo("用户：问题2\n助手：回答2\n用户：问题3\n助手：回答3")
                .doesNotContain("问题1"));
    }

    @Test
    void truncatesOverlongMessages() {
        append("i1", AgentEventType.AGENT_STARTED, "x".repeat(500), 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "ok", 2);

        Optional<String> history = service.history("session-1");

        assertThat(history).hasValueSatisfying(text -> assertThat(text)
                .hasSizeLessThan("用户：".length() + 500)
                .endsWith("…\n助手：ok"));
    }

    @Test
    void ordersEventsByTimestampRegardlessOfAppendOrder() {
        append("i2", AgentEventType.AGENT_STARTED, "第二个问题", 20);
        append("i2", AgentEventType.AGENT_COMPLETED, "第二个回答", 21);
        append("i1", AgentEventType.AGENT_STARTED, "第一个问题", 10);
        append("i1", AgentEventType.AGENT_COMPLETED, "第一个回答", 11);

        Optional<String> history = service.history("session-1");

        assertThat(history).hasValueSatisfying(text -> assertThat(text)
                .startsWith("用户：第一个问题")
                .endsWith("助手：第二个回答"));
    }

    /**
     * 每个创建运行的入口都要靠这一个注入点补历史。曾经只有 /api/agents/runs 注入，
     * 控制台实际走的后台运行入口没有，导致“我叫曾智”下一轮就失忆。
     */
    @Test
    void injectsHistoryIntoRequestAttributes() {
        append("i1", AgentEventType.AGENT_STARTED, "我叫曾智", 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "你好，曾智。", 2);

        AgentRequest enriched = service.withHistory(AgentRequest.of("session-1", "我是谁"));

        assertThat(enriched.objective()).isEqualTo("我是谁");
        assertThat(enriched.attributes())
                .containsEntry(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE,
                        "用户：我叫曾智\n助手：你好，曾智。");
    }

    /**
     * 多轮 thinking 模式：AGENT_COMPLETED 携带的 reasoningContent 必须编码进
     * 历史文本，HistoryProcessor 才能在下一轮请求回传 reasoning_content，
     * 否则 MiniMax 等思维链模型会拒绝请求（HTTP 400）。
     */
    @Test
    void encodesReasoningContentIntoAssistantHistoryLine() {
        String reasoning = "用户问日期 → 查日历 → 周三";
        String encoded = java.util.Base64.getEncoder().encodeToString(
                reasoning.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        append("i1", AgentEventType.AGENT_STARTED, "今天几号", 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "2026-08-19", 2,
                Map.of("reasoningContent", reasoning));

        Optional<String> history = service.history("session-1");

        assertThat(history).hasValueSatisfying(text -> assertThat(text)
                .isEqualTo("用户：今天几号\n助手：[reasoning=" + encoded + "]2026-08-19"));
        // 端到端链路：历史文本经 HistoryProcessor 解析后还原为带 reasoning 的 assistant 消息。
        assertThat(HistoryProcessor.parseHistory(history.orElse("")))
                .containsExactly(
                        com.github.agentos.planner.flow.LlmMessage.user("今天几号"),
                        com.github.agentos.planner.flow.LlmMessage.assistantWithReasoning(
                                "2026-08-19", reasoning));
    }

    @Test
    void keepsRequestUnchangedWhenSessionHasNoHistory() {
        AgentRequest request = new AgentRequest(
                "session-1", "你好", Map.of("source", "agentos-console"));

        AgentRequest enriched = service.withHistory(request);

        assertThat(enriched).isSameAs(request);
    }

    @Test
    void preservesCallerSuppliedHistory() {
        append("i1", AgentEventType.AGENT_STARTED, "事件里的问题", 1);
        append("i1", AgentEventType.AGENT_COMPLETED, "事件里的回答", 2);
        AgentRequest request = new AgentRequest("session-1", "我是谁", Map.of(
                HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE, "用户：外部历史"));

        assertThat(service.withHistory(request).attributes())
                .containsEntry(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE, "用户：外部历史");
    }

    @Test
    void marksRecentCompletedFileToolContextForFollowUpRouting() {
        append("i1", AgentEventType.AGENT_STARTED, "读取简历", 1);
        append("i1", AgentEventType.TOOL_CALL_COMPLETED, "工具调用完成", 2,
                Map.of("toolName", "file_read"));
        append("i1", AgentEventType.AGENT_COMPLETED, "已读取", 3);

        AgentRequest enriched = service.withHistory(
                AgentRequest.of("session-1", "一共待过哪几家公司？"));

        assertThat(enriched.attributes()).containsEntry(
                HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE, true);
    }

    @Test
    void ignoresFileToolContextFromIncompleteInvocation() {
        append("i1", AgentEventType.AGENT_STARTED, "读取简历", 1);
        append("i1", AgentEventType.TOOL_CALL_COMPLETED, "工具调用完成", 2,
                Map.of("toolName", "file_read"));

        AgentRequest enriched = service.withHistory(
                AgentRequest.of("session-1", "一共待过哪几家公司？"));

        assertThat(enriched.attributes()).doesNotContainKey(
                HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE);
    }

    private void append(String invocationId, AgentEventType type, String message, long epochSecond) {
        append(invocationId, type, message, epochSecond, Map.of());
    }

    private void append(
            String invocationId,
            AgentEventType type,
            String message,
            long epochSecond,
            Map<String, Object> data) {
        store.append(new AgentEventRecord(invocationId, type, message, epochSecond, data));
    }

    private record AgentEventRecord(
            String invocationId,
            AgentEventType type,
            String message,
            long epochSecond,
            Map<String, Object> data)
            implements AgentEvent {

        @Override
        public String eventId() {
            return invocationId + "-" + type + "-" + epochSecond;
        }

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public String agentId() {
            return "main-agent";
        }

        @Override
        public Instant timestamp() {
            return Instant.ofEpochSecond(epochSecond);
        }

    }
}
