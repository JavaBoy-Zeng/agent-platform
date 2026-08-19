package com.github.agentos.server.persistence;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.EventActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQLite 事件存储测试：验证事件（含 EventActions）落库与查询往返一致。 */
class SqliteAgentEventStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SqliteAgentEventStore store(Path file) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return new SqliteAgentEventStore(dataSource, objectMapper);
    }

    @Test
    void persistsActionsAndReplaysAcrossRestart() {
        Path file = tempDir.resolve("events.sqlite");
        SqliteAgentEventStore first = store(file);
        first.append(new DefaultAgentEvent(
                "e1", "session-1", "inv-1", "main-agent",
                Instant.parse("2026-08-19T10:00:00Z"),
                AgentEventType.TOOL_CALL_COMPLETED, "weather done", Map.of(),
                EventActions.stateDelta(Map.of("city", "重庆"))));
        first.append(new DefaultAgentEvent(
                "e2", "session-1", "inv-1", "main-agent",
                Instant.parse("2026-08-19T10:00:01Z"),
                AgentEventType.AGENT_COMPLETED, "28℃", Map.of("status", "COMPLETED"),
                EventActions.stateDelta(Map.of("turnCount", 1L))));

        // 模拟进程重启后读取。
        SqliteAgentEventStore second = store(file);
        List<AgentEvent> events = second.findBySessionId("session-1");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).actions().stateDelta()).containsEntry("city", "重庆");
        assertThat(events.get(1).actions().stateDelta().get("turnCount"))
                .isEqualTo(1);
    }

    @Test
    void plainEventsKeepNoneActions() {
        SqliteAgentEventStore store = store(tempDir.resolve("events.sqlite"));
        store.append(new DefaultAgentEvent(
                "e1", "session-1", "inv-1", "main-agent",
                Instant.parse("2026-08-19T10:00:00Z"),
                AgentEventType.AGENT_STARTED, "hi", Map.of()));

        List<AgentEvent> events = store.findByInvocationId("inv-1");

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.actions()).isEqualTo(EventActions.NONE));
    }
}
