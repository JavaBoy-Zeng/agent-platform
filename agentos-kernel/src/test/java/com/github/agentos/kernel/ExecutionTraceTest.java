package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceTest {
    @TempDir Path directory;

    @Test void archivesFullChildRecordUnderRootSessionAndSurvivesReload() {
        var events = new ArrayList<AgentEvent>();
        var root = new AgentInvocation("root", "session", "react-agent", "", Instant.now());
        var child = new AgentInvocation("root/sub/workspace/child", "child-session", "workspace-agent", "", Instant.now());
        child.shareModelCallBudget(root);
        var context = InvocationContext.of("workspace-agent").withRuntime(child, events::add)
                .withArtifacts(new LocalArtifactService(directory));
        String content = "开头\n" + "完整内容".repeat(10_000) + "\n失败尾部";
        try (var scope = ExecutionTrace.open(context)) {
            ExecutionTrace.recordCurrent("call-1", "model_response", "模型返回", content);
        }
        ExecutionTrace.recordCurrent("outside", "model_response", "不在任务作用域", "ignored");
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.sessionId()).isEqualTo("session");
        assertThat(event.invocationId()).isEqualTo("root");
        assertThat(event.data()).containsEntry("executionInvocationId", child.invocationId());
        assertThat(String.valueOf(event.data().get("text")).length()).isLessThan(4_100);
        var reloaded = new LocalArtifactService(directory).load(String.valueOf(event.data().get("artifactId"))).orElseThrow();
        assertThat(new String(reloaded.bytes(), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test void recordsPartialResponseAfterCancellationAndRestoresInterrupt() {
        var events = new ArrayList<AgentEvent>();
        var context = InvocationContext.of("react-agent")
                .withRuntime(new AgentInvocation("r", "s", "react-agent", "", Instant.now()), events::add)
                .withArtifacts(new LocalArtifactService(directory));
        Thread.currentThread().interrupt();
        try {
            ExecutionTrace.record(context, "call", "model_response", "部分返回", "data: 部分推理内容");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(events.getFirst().data()).containsEntry("archived", true);
        } finally { Thread.interrupted(); }
    }

    @Test void unavailableArchiveKeepsFullTextAndReportsItsStatus() {
        var events = new ArrayList<AgentEvent>();
        var invocation = new AgentInvocation("root", "session", "react-agent", "", Instant.now());
        var context = InvocationContext.of("react-agent").withRuntime(invocation, events::add);
        String text = "保留全部".repeat(3_000);
        ExecutionTrace.record(context, "call-1", "tool_result", "结果", text);
        assertThat(events.getFirst().data()).containsEntry("archived", false).containsEntry("text", text);
    }
}
