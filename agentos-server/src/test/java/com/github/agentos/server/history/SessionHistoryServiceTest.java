package com.github.agentos.server.history;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.InMemoryAgentEventStore;
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

    private void append(String invocationId, AgentEventType type, String message, long epochSecond) {
        store.append(new AgentEventRecord(invocationId, type, message, epochSecond));
    }

    private record AgentEventRecord(
            String invocationId, AgentEventType type, String message, long epochSecond)
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

        @Override
        public Map<String, Object> data() {
            return Map.of();
        }
    }
}
