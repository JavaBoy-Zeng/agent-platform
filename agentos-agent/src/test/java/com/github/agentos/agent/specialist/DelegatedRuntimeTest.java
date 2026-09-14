package com.github.agentos.agent.specialist;

import com.github.agentos.agent.loop.*;
import com.github.agentos.agent.routing.*;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.kernel.*;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.*;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DelegatedRuntimeTest {
    @Test
    void exhaustedChildReturnsEvidenceAndRootFinishesWithinSharedBudget() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        List<AgentEvent> progress = new ArrayList<>();
        AgentTool command = command(commands);
        ChatClient childChat = new ChatClient() {
            public String chat(String session, LlmRequest input) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, LlmRequest input, Consumer<String> delta) {
                assertThat(session).isNotEqualTo("root-session");
                assertThat(ModelUsageScope.sessionId(session)).isEqualTo("root-session");
                assertThat(input.messages()).noneMatch(m -> m.content().contains("OLD_HISTORY"));
                assertThat(ConversationCompactor.totalChars(input.messages())).isLessThan(17_000);
                int call = modelCalls.incrementAndGet();
                return ToolCallResponse.call(new ToolCall("run_command", Map.of("command", "mvn test")), "child-" + call, null);
            }
        };
        var child = group(command, childChat, 10);
        var adapter = new AgentToolAdapter(child);
        ChatClient rootChat = new ChatClient() {
            public String chat(String session, LlmRequest input) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, LlmRequest input, Consumer<String> delta) {
                int call = modelCalls.incrementAndGet();
                if (call == 1) return ToolCallResponse.call(new ToolCall("workspace-agent",
                        Map.of("objective", "run tests")), "delegate", null);
                assertThat(call).isEqualTo(4);
                assertThat(input.tools()).isEmpty();
                assertThat(input.messages()).anyMatch(m -> m.content().contains("BUILD FAILURE")
                        && m.content().contains("子任务未完成"));
                return ToolCallResponse.answer("测试失败，已取得错误；进一步定位尚未完成。", null);
            }
        };
        var root = new ReactAgent(rootChat, new ToolDispatcher(new ToolRegistry(List.of(adapter))),
                List.of(adapter), new AgentExecutionLimits(0, 20, 20, 4),
                new ConversationCompactor(16_000, 6_000, 1_000), ContinuationStore.NOOP, 0, 0);
        var runner = new AgentRunner(root);
        List<AgentRunEvent> streamed = new ArrayList<>();
        var execution = runner.runDetailed(new AgentRequest("root-session", "调查测试", Map.of("conversationHistory", "用户：OLD_HISTORY")),
                InvocationContext.of("react-agent"), streamed::add, progress::add);
        var invocation = runner.invocation(execution.invocationId()).orElseThrow();
        assertThat(streamed).anyMatch(e -> e.type() == AgentRunEvent.Type.TOOL_FINISHED
                && "workspace-agent".equals(e.data().get("subagentId"))
                && e.sessionId().equals("root-session"));
        assertThat(execution.state().status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(modelCalls).hasValue(4);
        assertThat(commands).hasValue(2);
        assertThat(invocation.totalModelCalls()).isEqualTo(4);
        assertThat(progress).anyMatch(e -> "workspace-agent".equals(e.data().get("subagentId"))
                && "TOOL_FINISHED".equals(e.data().get("runEventType")));
    }

    @Test
    void repeatedDelegationsCannotResetBudgetAndKeepPartialResults() {
        AtomicInteger commands = new AtomicInteger();
        AtomicInteger models = new AtomicInteger();
        var command = command(commands);
        ChatClient chat = new ChatClient() {
            public String chat(String session, LlmRequest input) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, LlmRequest input, Consumer<String> delta) {
                return ToolCallResponse.call(new ToolCall("run_command", Map.of("command", "mvn test")),
                        "call-" + models.incrementAndGet(), null);
            }
        };
        var adapter = new AgentToolAdapter(group(command, chat, 2));
        var invocation = new AgentInvocation("root", "root-session", "react-agent", "", Instant.now());
        invocation.configureModelCallBudget(5, 0);
        var context = new ToolContext(AgentRequest.of("root-session", "test"),
                InvocationContext.of("react-agent").withInvocation(invocation), "", "",
                AgentExecutionLimits.defaults(), Map.of(), adapter);
        for (int i = 0; i < 3; i++) {
            var result = adapter.execute(context, new ToolCall("workspace-agent", Map.of("objective", "test")));
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("未完成");
            if (i < 2) assertThat(result.error()).contains("BUILD FAILURE", "mvn test");
        }
        assertThat(models).hasValue(4);
        assertThat(commands).hasValue(4);
        assertThat(invocation.remainingModelCalls()).isEqualTo(1);
    }

    @Test
    void successfulFileReadReturnsMetadataWithoutRepeatingWholeFile() {
        AtomicInteger rounds = new AtomicInteger();
        String metadata = "[file_read_metadata]\npath=/tmp/pom.xml\nhasMore=false\n[/file_read_metadata]";
        AgentTool file = new AgentTool() {
            public String name() { return "file_read"; }
            public String description() { return "read"; }
            public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("FILE_CONTENT".repeat(2000) + "\n" + metadata);
            }
        };
        ChatClient chat = new ChatClient() {
            public String chat(String session, LlmRequest input) { throw new AssertionError(); }
            public ToolCallResponse chatWithTools(String session, LlmRequest input, Consumer<String> delta) {
                if (rounds.incrementAndGet() == 1) return ToolCallResponse.call(
                        new ToolCall("file_read", Map.of("path", "/tmp/pom.xml")), "read", null);
                assertThat(ConversationCompactor.totalChars(input.messages())).isLessThan(7_000);
                assertThat(input.messages().getLast().content()).contains(metadata);
                return ToolCallResponse.answer("已确认构建配置", null);
            }
        };
        var result = group(file, chat, 3).run(AgentRequest.of("s", "read"),
                InvocationContext.of("workspace-agent"), AgentState.ready().startNextIteration());
        assertThat(result.output()).contains(metadata, "已确认构建配置").doesNotContain("FILE_CONTENT");
        assertThat(result.output().length()).isLessThan(500);
    }

    private static ToolProviderAgent group(AgentTool tool, ChatClient chat, int models) {
        var agent = new ToolProviderAgent("workspace-agent", "workspace", "执行任务",
                Set.of(AgentCapability.COMMAND_EXECUTION), Set.of(RouteScope.LOCAL_WORKSPACE),
                ctx -> List.of(tool), chat);
        agent.configureExecution(new ToolDispatcher(new ToolRegistry(List.of(tool))), ContinuationStore.NOOP,
                new AgentExecutionLimits(0, 20, 20, models));
        return agent;
    }

    private static AgentTool command(AtomicInteger calls) {
        return new AgentTool() {
            public String name() { return "run_command"; }
            public String description() { return "command"; }
            public ToolResult execute(ToolContext context, ToolCall call) {
                assertThat(context.request().sessionId()).isNotEqualTo("root-session");
                calls.incrementAndGet();
                return ToolResult.success("test output\n" + "verbose log ".repeat(3000) + "\nBUILD FAILURE: assertion failed");
            }
        };
    }
}
