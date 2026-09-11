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
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "must not run";
        };
        AgentTool webSearch = functionalTool("web_search", call -> {
            searchCalls.incrementAndGet();
            return ToolResult.success("must not run");
        });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = new SearchAgent(chatClient, List.of(webSearch)).run(
                AgentRequest.of("session-1", "当前 AgentOS 有哪些工具"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("LOCAL_RUNTIME_SCOPE_MISMATCH");
        assertThat(modelCalls).hasValue(0);
        assertThat(searchCalls).hasValue(0);
        assertThat(events).anyMatch(event -> event.type() == AgentRunEvent.Type.ROUTE_REJECTED);
    }

    @Test
    void mergesResultsFromBothSearchToolsIntoTwoIndependentSources() {
        StringBuilder summarizePrompt = new StringBuilder();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return "重庆 Agent 开发薪资";
            }
            summarizePrompt.append(request.messages().get(0).content());
            return "多来源薪资摘要";
        };
        AgentTool browserSearch = constantTool("browser_search", ToolResult.success("""
                1. 薪资报告 A
                   https://salary.example.org/report
                   重庆 Agent 开发者薪资区间与样本说明
                """));
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 薪资报告 B
                   https://research.example.net/ai-agent
                   重庆 AI Agent 岗位薪资统计口径
                """));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(browserSearch, webSearch), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(summarizePrompt.toString())
                .contains("https://salary.example.org/report")
                .contains("https://research.example.net/ai-agent");
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .extracting(event -> event.data().get("toolName"))
                .containsExactly("browser_search", "web_search");
    }

    @Test
    void deduplicatesSameHostAcrossToolsAndRequiresTwoDistinctHosts() {
        ChatClient chatClient = answers("重庆 Agent 开发薪资", "摘要");
        AgentTool browserSearch = constantTool("browser_search", ToolResult.success("""
                1. 报告 A
                   https://salary.example.org/report
                   薪资数据 A
                """));
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 报告 A 副本
                   https://salary.example.org/another
                   薪资数据 B
                """));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(browserSearch, webSearch), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("至少需要 2 个不同站点", "实际获得 1 个");
        assertThat(decision(events).data()).containsEntry("reason", "INSUFFICIENT_SEARCH_EVIDENCE");
    }

    @Test
    void failsWithoutAskingModelToGuessUrlWhenSearchToolsAreMissing() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "https://guessed.example.com";
        };
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("搜索工具未配置", "browser_search");
        assertThat(modelCalls).hasValue(0);
        assertThat(decision(events).data()).containsEntry("reason", "SEARCH_UNAVAILABLE");
    }

    @Test
    void continuesWithRemainingToolsWhenOneSearchToolFails() {
        ChatClient chatClient = answers("重庆 Agent 开发薪资", "多来源摘要");
        AgentTool brokenSearch = constantTool("browser_search",
                ToolResult.failure(ToolFailureType.TRANSIENT, "connection refused"));
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 报告 A
                   https://salary.example.org/report
                   重庆 Agent 薪资区间
                2. 报告 B
                   https://research.example.net/ai-agent
                   重庆 AI Agent 岗位统计
                """));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(brokenSearch, webSearch), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_FINISHED
                        && "browser_search".equals(event.data().get("toolName")))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("status", "FAILED"));
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
        AgentTool browserSearch = sequenceTool("browser_search",
                ToolResult.failure(ToolFailureType.TRANSIENT, "HTTP 429"),
                ToolResult.success("""
                        1. 报告 A
                           https://salary.example.org/report
                           薪资数据 A
                        2. 报告 B
                           https://research.example.net/report
                           薪资数据 B
                        """));
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 报告 C
                   https://jobs.example.com/salary
                   薪资数据 C
                """));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(browserSearch, webSearch), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(modelCalls).hasValue(3);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_FINISHED)
                .filteredOn(event -> "browser_search".equals(event.data().get("toolName")))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("attempts", 2));
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_FINISHED)
                .filteredOn(event -> "web_search".equals(event.data().get("toolName")))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("attempts", 1));
    }

    @Test
    void eventLabelsFollowInjectedToolNamesInsteadOfHardcodedOnes() {
        ChatClient chatClient = answers("Java 21 发布时间", "多来源摘要");
        AgentTool browserSearch = constantTool("browser_search", ToolResult.success("""
                1. 来源 A
                   https://salary.example.org/report
                   内容 A
                """));
        AgentTool webSearch = constantTool("web_search", ToolResult.success("""
                1. 来源 B
                   https://research.example.net/ai-agent
                   内容 B
                """));
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = run(chatClient, List.of(browserSearch, webSearch), events);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED)
                .extracting(event -> event.data().get("toolName"))
                .containsExactly("browser_search", "web_search");
    }

    @Test
    void fetchesTwoSearchResultsBeforeSummarizing() {
        StringBuilder summarizePrompt = new StringBuilder();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return "Agent 开发薪资";
            }
            summarizePrompt.append(request.messages().get(0).content());
            return "基于网页正文的摘要";
        };
        AgentTool browserSearch = constantTool("browser_search", ToolResult.success("""
                1. 来源 A
                   https://a.example.org/report
                   搜索摘要 A
                2. 来源 B
                   https://b.example.net/report
                   搜索摘要 B
                """));
        List<String> fetchedUrls = new ArrayList<>();
        AgentTool webFetch = functionalTool("web_fetch", call -> {
            String url = String.valueOf(call.arguments().get("url"));
            fetchedUrls.add(url);
            return ToolResult.success(("这是从 " + url + " 抓取到的网页正文，")
                    .repeat(8));
        });

        AgentState result = new SearchAgent(chatClient, List.of(browserSearch), webFetch).run(
                AgentRequest.of("session-1", "搜索 Agent 开发重庆薪资"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(), event -> { });

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(fetchedUrls).containsExactly(
                "https://a.example.org/report", "https://b.example.net/report");
        assertThat(summarizePrompt.toString()).contains("抓取到的网页正文");
    }

    @Test
    void explicitUrlSkipsSearchAndQueryGeneration() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger fetchCalls = new AtomicInteger();
        ChatClient chatClient = (sessionId, request) -> {
            modelCalls.incrementAndGet();
            return "页面资料摘要";
        };
        AgentTool browserSearch = functionalTool("browser_search", call -> {
            searchCalls.incrementAndGet();
            return ToolResult.success("must not run");
        });
        AgentTool webFetch = functionalTool("web_fetch", call -> {
            fetchCalls.incrementAndGet();
            assertThat(call.arguments().get("url"))
                    .isEqualTo("https://example.org/article");
            return ToolResult.success("明确 URL 返回的完整网页正文。".repeat(12));
        });

        AgentState result = new SearchAgent(chatClient, List.of(browserSearch), webFetch).run(
                AgentRequest.of("session-1", "总结 https://example.org/article 需要哪些资料"),
                InvocationContext.of(SearchAgent.ID),
                AgentState.ready().startNextIteration(), event -> { });

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(searchCalls).hasValue(0);
        assertThat(fetchCalls).hasValue(1);
        assertThat(modelCalls).hasValue(1);
    }

    private static AgentState run(
            ChatClient chatClient,
            List<AgentTool> searchTools,
            List<AgentRunEvent> events) {
        return new SearchAgent(chatClient, searchTools).run(
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
