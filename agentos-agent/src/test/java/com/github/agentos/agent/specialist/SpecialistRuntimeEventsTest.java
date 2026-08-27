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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialistRuntimeEventsTest {

    @Test
    void searchAgentEmitsPlanToolObservationAndDecisionEvents() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> modelCalls.getAndIncrement() == 0
                ? "AgentOS"
                : "AgentOS 是一个 Agent 平台。";
        AgentTool webSearch = tool("web_search", ToolResult.success(
                "https://example.com/agentos 搜索结果\n"
                        + "https://example.org/agentos 另一条搜索结果"));
        AgentTool webFetch = tool("web_fetch", ToolResult.success(
                "AgentOS 页面正文与产品说明。".repeat(30)));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = new SearchAgent(chatClient, webSearch, webFetch).run(
                AgentRequest.of("session-1", "搜索 AgentOS 是什么"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.PLAN_CREATED,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .extracting(event -> event.data().get("toolName"))
                .containsExactly("web_search", "web_fetch", "web_fetch");
    }

    @Test
    void codeAgentEmitsPlanToolObservationAndDecisionEvents() {
        ChatClient chatClient = (sessionId, request) -> "# python\nprint('ok')";
        AgentTool fileWrite = tool("file_write", ToolResult.success("written"));
        AgentTool runCommand = tool("run_command", ToolResult.success("ok"));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = new CodeAgent(chatClient, fileWrite, runCommand).run(
                AgentRequest.of("session-1", "写一段 Python 代码"),
                InvocationContext.of(CodeAgent.ID),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.PLAN_CREATED,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .extracting(event -> event.data().get("toolName"))
                .containsExactly("file_write", "run_command");
    }

    private static AgentTool tool(String name, ToolResult result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test " + name;
            }

            @Override
            public ToolResult execute(ToolContext context, ToolCall call) {
                assertThat(call.toolName()).isEqualTo(name);
                return result;
            }
        };
    }
}
