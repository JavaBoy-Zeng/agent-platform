package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.ChatStreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行事件到对话展示事件的映射测试。
 *
 * <p>核心约束：FILE_READ / FILE_EDITED / COMMAND_EXECUTED 只能由携带真实
 * toolName 的 TOOL_FINISHED 事件推导，任何模型文本都影响不了这里的分类结果。</p>
 */
class ChatEventMapperTest {

    @Test
    void mapsOutputDeltaToAssistantMessage() {
        ChatStreamEvent mapped = ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.OUTPUT_DELTA, "s1", "hello ",
                Map.of("sequence", 0, "source", "model-sse")));

        assertThat(mapped).isNotNull();
        assertThat(mapped.type()).isEqualTo(ChatStreamEvent.Type.ASSISTANT_MESSAGE);
        assertThat(mapped.message()).isEqualTo("hello ");
        assertThat(mapped.sessionId()).isEqualTo("s1");
        assertThat(mapped.data()).containsEntry("sequence", 0);
        assertThat(mapped.data()).containsEntry("sourceEvent", "output_delta");
    }

    @Test
    void mapsToolFinishedByRealToolName() {
        assertThat(mapToolFinished("file_read").type())
                .isEqualTo(ChatStreamEvent.Type.FILE_READ);
        assertThat(mapToolFinished("file_write").type())
                .isEqualTo(ChatStreamEvent.Type.FILE_EDITED);
        assertThat(mapToolFinished("run_command").type())
                .isEqualTo(ChatStreamEvent.Type.COMMAND_EXECUTED);
        assertThat(mapToolFinished("web_search").type())
                .isEqualTo(ChatStreamEvent.Type.TOOL_COMPLETED);
    }

    @Test
    void toolFinishedCarriesArgumentsAndSummary() {
        ChatStreamEvent mapped = ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_FINISHED, "s1", "工具执行完成",
                Map.of(
                        "toolName", "file_read",
                        "arguments", Map.of("path", "README.md"),
                        "success", true,
                        "summary", "共 120 行")));

        assertThat(mapped.type()).isEqualTo(ChatStreamEvent.Type.FILE_READ);
        assertThat(mapped.data()).containsEntry("toolName", "file_read");
        assertThat(mapped.data()).containsEntry("summary", "共 120 行");
        assertThat(mapped.data()).containsEntry("success", true);
    }

    @Test
    void toolStartedKeepsArguments() {
        ChatStreamEvent mapped = ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_STARTED, "s1", "执行 run_command",
                Map.of(
                        "toolName", "run_command",
                        "arguments", Map.of("command", "mvn -v"))));

        assertThat(mapped.type()).isEqualTo(ChatStreamEvent.Type.TOOL_STARTED);
        assertThat(mapped.data()).containsEntry("toolName", "run_command");
        assertThat(mapped.data())
                .containsEntry("arguments", Map.of("command", "mvn -v"));
    }

    @Test
    void mapsTerminalAndProgressEvents() {
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_COMPLETED, "s1", "final answer", Map.of())).type())
                .isEqualTo(ChatStreamEvent.Type.FINAL_ANSWER);
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_FAILED, "s1", "boom", Map.of())).type())
                .isEqualTo(ChatStreamEvent.Type.ERROR);
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_CANCELLED, "s1", "cancelled", Map.of())).type())
                .isEqualTo(ChatStreamEvent.Type.ERROR);
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.PLAN_CREATED, "s1", "created", Map.of())).type())
                .isEqualTo(ChatStreamEvent.Type.PROGRESS);
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.REPLAN, "s1", "replan", Map.of())).type())
                .isEqualTo(ChatStreamEvent.Type.PROGRESS);
    }

    @Test
    void filtersObservationAndUsageNoise() {
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.USAGE, "s1", "tokens", Map.of()))).isNull();
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.OBSERVATION, "s1", "observed", Map.of()))).isNull();
        assertThat(ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.ROUTE_REJECTED, "s1", "rejected", Map.of()))).isNull();
        assertThat(ChatEventMapper.map(null)).isNull();
    }

    @Test
    void preservesTimestampAndUsesSnakeCaseEventName() {
        Instant occurredAt = Instant.parse("2026-08-28T08:00:00Z");
        ChatStreamEvent mapped = ChatEventMapper.map(new AgentRunEvent(
                AgentRunEvent.Type.OUTPUT_DELTA, "s1", "hi", Map.of(), occurredAt));

        assertThat(mapped.timestamp()).isEqualTo(occurredAt);
        assertThat(ChatEventMapper.eventName(mapped)).isEqualTo("assistant_message");
        assertThat(ChatEventMapper.eventName(ChatStreamEvent.of(
                ChatStreamEvent.Type.COMMAND_EXECUTED, "s1", "", Map.of())))
                .isEqualTo("command_executed");
    }

    private static ChatStreamEvent mapToolFinished(String toolName) {
        ChatStreamEvent mapped = ChatEventMapper.map(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_FINISHED, "s1", "done",
                Map.of("toolName", toolName, "success", true, "summary", "ok")));
        assertThat(mapped).isNotNull();
        return mapped;
    }
}
