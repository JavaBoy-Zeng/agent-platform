package com.github.agentos.agent.config;

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

class ConfigDrivenAgentRuntimeEventsTest {

    @Test
    void emitsUnifiedRuntimeEventsWhenConfiguredToolIsCalled() {
        AgentDefinition definition = new AgentDefinition(
                "research-agent",
                "specialist",
                "Research a topic",
                "You are a research expert",
                List.of("web_search"),
                false,
                List.of(),
                true);
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> modelCalls.getAndIncrement() == 0
                ? "web_search"
                : "AgentOS 是一个 Agent 平台。";
        AgentTool webSearch = new AgentTool() {
            @Override
            public String name() {
                return "web_search";
            }

            @Override
            public String description() {
                return "Search the web";
            }

            @Override
            public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("AgentOS search result");
            }
        };
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = new ConfigDrivenAgent(
                definition, chatClient, List.of(webSearch)).run(
                AgentRequest.of("session-1", "搜索 AgentOS"),
                InvocationContext.of(definition.id()),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.PLAN_CREATED,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .singleElement()
                .satisfies(event -> assertThat(event.data().get("toolName"))
                        .isEqualTo("web_search"));
    }
}
