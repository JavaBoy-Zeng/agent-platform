package com.github.agentos.agent.specialist;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAgentTest {

    @Test
    void rejectsLocalRuntimeQuestionBeforeModelOrWebTools() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger fetchCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "must not run";
        };
        AgentTool webSearch = functionalTool("web_search", call -> {
            searchCalls.incrementAndGet();
            return ToolResult.success("must not run");
        });
        AgentTool webFetch = functionalTool("web_fetch", call -> {
            fetchCalls.incrementAndGet();
            return ToolResult.success("must not run");
        });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = new SearchAgent(chatClient, webSearch, webFetch).run(
                AgentRequest.of("session-1", "当前 AgentOS 有哪些工具"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(), events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("LOCAL_RUNTIME_SCOPE_MISMATCH");
        assertThat(modelCalls).hasValue(0);
        assertThat(searchCalls).hasValue(0);
        assertThat(fetchCalls).hasValue(0);
        assertThat(events).anyMatch(event -> event.type() == AgentRunEvent.Type.ROUTE_REJECTED);
    }

    @Test
    void rejectsInvalidPageAndSwitchesToTwoValidIndependentSources() {
        ChatClient chatClient = answers("重庆 Agent 开发薪资", "多来源薪资摘要");
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 动态页面
                   https://jobs.example.com/chongqing
                2. 报告 A
                   https://salary.example.org/report
                3. 报告 B
                   https://research.example.net/ai-agent
                """));
        AtomicInteger fetchCalls = new AtomicInteger();
        AgentTool webFetch = functionalTool("web_fetch", call -> switch (fetchCalls.getAndIncrement()) {
            case 0 -> ToolResult.success("加载中，请稍候");
            case 1 -> ToolResult.success(validContent("来源 A"));
            default -> ToolResult.success(validContent("来源 B"));
        });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, webSearch, webFetch, events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("多来源薪资摘要");
        assertThat(fetchCalls).hasValue(3);
        assertThat(events)
                .filteredOn(event -> event.type() == AgentRunEvent.Type.OBSERVATION)
                .filteredOn(event -> "REJECTED".equals(event.data().get("status")))
                .singleElement()
                .satisfies(event -> assertThat(event.message())
                        .contains("正文无效", "继续尝试其他来源"));
        AgentRunEvent decision = decision(events);
        assertThat(decision.data())
                .containsEntry("outcome", "COMPLETE")
                .containsEntry("reason", "SEARCH_SUMMARIZED")
                .containsEntry("observationCount", 2);
    }

    @Test
    void failsInsteadOfCompletingWhenFewerThanTwoSourcesAreValid() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "重庆 Agent 开发薪资";
        };
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                https://salary.example.org/report
                https://jobs.example.com/chongqing
                """));
        AtomicInteger fetchCalls = new AtomicInteger();
        AgentTool webFetch = functionalTool("web_fetch", call -> fetchCalls.getAndIncrement() == 0
                ? ToolResult.success(validContent("唯一有效来源"))
                : ToolResult.success("请启用 JavaScript 完成人机验证码验证。".repeat(20)));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, webSearch, webFetch, events);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("有效来源不足", "实际获得 1 个");
        assertThat(modelCalls).hasValue(1);
        assertThat(events).noneMatch(event -> event.type() == AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).noneMatch(event -> event.type() == AgentRunEvent.Type.OUTPUT_DELTA);
        assertThat(decision(events).data())
                .containsEntry("outcome", "ABORT")
                .containsEntry("reason", "INSUFFICIENT_SEARCH_EVIDENCE");
    }

    @Test
    void failsWithoutAskingModelToGuessUrlWhenSearchServiceIsMissing() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "https://guessed.example.com";
        };
        AgentTool webFetch = constantTool("web_fetch", ToolResult.success(validContent("内容")));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, null, webFetch, events);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("web_search 未配置", "API Key");
        assertThat(modelCalls).hasValue(0);
        assertThat(decision(events).data()).containsEntry("reason", "SEARCH_UNAVAILABLE");
    }

    @Test
    void retriesTransientToolAndModelFailuresWithAttemptCount() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                int call = modelCalls.incrementAndGet();
                if (call == 1) {
                    throw new IllegalStateException("Chat endpoint returned HTTP 503");
                }
                return call == 2 ? "Agent 开发薪资" : "重试后的摘要";
            }
        };
        AgentTool webSearch = sequenceTool("web_search",
                ToolResult.failure(ToolFailureType.TRANSIENT, "HTTP 429"),
                ToolResult.success("""
                        https://salary.example.org/report
                        https://research.example.net/report
                        """));
        AtomicInteger fetchCalls = new AtomicInteger();
        AgentTool webFetch = functionalTool("web_fetch", call ->
                fetchCalls.getAndIncrement() == 0
                        ? ToolResult.failure(ToolFailureType.TIMEOUT, "fetch timeout")
                        : ToolResult.success(validContent("薪资数据")));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, webSearch, webFetch, events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(modelCalls).hasValue(3);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_FINISHED)
                .filteredOn(event -> "web_search".equals(event.data().get("toolName")))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("attempts", 2));
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_FINISHED)
                .filteredOn(event -> "web_fetch".equals(event.data().get("toolName")))
                .first()
                .satisfies(event -> assertThat(event.data()).containsEntry("attempts", 2));
    }

    private static AgentState run(
            ChatClient chatClient,
            AgentTool webSearch,
            AgentTool webFetch,
            List<AgentRunEvent> events) {
        return new SearchAgent(chatClient, webSearch, webFetch).run(
                AgentRequest.of("session-1", "搜索 Agent 开发重庆薪资"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(),
                events::add);
    }

    private static AgentRunEvent decision(List<AgentRunEvent> events) {
        return events.stream()
                .filter(event -> event.type() == AgentRunEvent.Type.DECISION)
                .findFirst()
                .orElseThrow();
    }

    private static ChatClient answers(String... answers) {
        Deque<String> queue = new ArrayDeque<>(List.of(answers));
        return (sessionId, request) -> queue.removeFirst();
    }

    private static String validContent(String prefix) {
        return (prefix + "：这是经过抓取的公开资料正文，包含岗位、经验要求、薪资区间、"
                + "样本范围、统计口径和发布时间等可核验信息。").repeat(8);
    }

    private static AgentTool constantTool(String name, ToolResult result) {
        return functionalTool(name, call -> result);
    }

    private static AgentTool sequenceTool(String name, ToolResult... results) {
        Deque<ToolResult> queue = new ArrayDeque<>(List.of(results));
        return functionalTool(name, call -> queue.removeFirst());
    }

    private static AgentTool functionalTool(
            String name, java.util.function.Function<ToolCall, ToolResult> implementation) {
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
                return implementation.apply(call);
            }
        };
    }
}
