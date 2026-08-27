package com.github.agentos.agent.specialist;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReportAgentTest {

    @TempDir
    Path outputDirectory;

    @Test
    void writesSanitizedDocumentWithReadableNonOverwritingFileName() throws Exception {
        String objective = "请生成并保存一份关于抖音视频 Agent Skill过多 4招提升命中率";
        Files.writeString(outputDirectory.resolve(
                "抖音视频-Agent-Skill过多-4招提升命中率.md"), "existing");
        String rawAnswer = """
                    <think>不能进入文件的推理</think>
                    ```markdown
                    # 顶部引用块

                    ## 建议

                    只保留最终内容。
                    ```
                    """;
        ChatClient chatClient = (sessionId, request) -> rawAnswer;
        AtomicReference<ToolCall> capturedCall = new AtomicReference<>();
        AgentTool writeTool = new AgentTool() {
            @Override
            public String name() {
                return "file_write";
            }

            @Override
            public String description() {
                return "test file writer";
            }

            @Override
            public ToolResult execute(ToolContext context, ToolCall call) {
                capturedCall.set(call);
                return ToolResult.success(Map.of("artifactId", "artifact-1"));
            }
        };
        String originalUserDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", outputDirectory.toString());
        AgentState result;
        List<AgentRunEvent> events = new ArrayList<>();
        try {
            result = new ReportAgent(chatClient, writeTool).run(
                    AgentRequest.of("session-1", objective),
                    InvocationContext.of(ReportAgent.ID),
                    AgentState.ready().startNextIteration(),
                    events::add);
        } finally {
            System.setProperty("user.dir", originalUserDirectory);
        }

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(capturedCall.get().arguments())
                .containsEntry("mode", "CREATE_NEW")
                .containsEntry("createParentDirectories", true)
                .containsEntry("path", outputDirectory.resolve(
                        "抖音视频-Agent-Skill过多-4招提升命中率-2.md").toString());
        assertThat((String) capturedCall.get().arguments().get("content"))
                .startsWith("# 抖音视频 Agent Skill过多 4招提升命中率\n")
                .contains("只保留最终内容。")
                .doesNotContain("think", "```markdown", "顶部引用块");
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.PLAN_CREATED,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("toolName", "file_write"));
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.OBSERVATION)
                .singleElement()
                .satisfies(event -> assertThat(event.message())
                        .contains("文档已生成并保存"));
    }
}
