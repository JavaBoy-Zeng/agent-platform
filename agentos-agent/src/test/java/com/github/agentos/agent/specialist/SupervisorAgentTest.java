package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.AgentCapability;
import com.github.agentos.agent.routing.RouteAcceptance;
import com.github.agentos.agent.routing.RoutableAgent;
import com.github.agentos.agent.routing.SupervisorRouteDecision;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.LlmRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorAgentTest {

    @Test
    void dispatchesOnlyAfterCapabilityAcceptance() {
        StubRoutableAgent search = new StubRoutableAgent(true);
        AtomicInteger fallbackCalls = new AtomicInteger();
        SupervisorAgent supervisor = supervisor(json(
                "EXTERNAL_WORLD", "WEB_RESEARCH", "search-agent", 0.94, ""),
                search, fallbackCalls, new AtomicReference<>());

        AgentState result = supervisor.run(
                new AgentRequest("s1", "Agent 开发薪资",
                        Map.of("approvalMode", "FULL_ACCESS")),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        assertThat(result.output()).isEqualTo("searched");
        assertThat(search.calls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void approvalModesRouteSpecialistsThroughMainAgentToolDispatcher() {
        StubRoutableAgent search = new StubRoutableAgent(true);
        AtomicInteger fallbackCalls = new AtomicInteger();
        List<AgentRunEvent> events = new ArrayList<>();
        SupervisorAgent supervisor = supervisor(json(
                "EXTERNAL_WORLD", "WEB_RESEARCH", "search-agent", 0.94, ""),
                search, fallbackCalls, new AtomicReference<>());

        AgentState result = supervisor.run(
                new AgentRequest("s1", "搜索 AgentOS",
                        Map.of("approvalMode", "REQUEST_APPROVAL")),
                InvocationContext.of("supervisor-agent"), running(), events::add);

        assertThat(result.output()).isEqualTo("fallback");
        assertThat(search.calls).hasValue(0);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.ROUTE_REJECTED)
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("rejectionCode", "CENTRAL_APPROVAL_REQUIRED"));
    }

    @Test
    void rejectedSpecialistFallsBackBeforeExecution() {
        StubRoutableAgent search = new StubRoutableAgent(false);
        AtomicInteger fallbackCalls = new AtomicInteger();
        List<AgentRunEvent> events = new ArrayList<>();
        SupervisorAgent supervisor = supervisor(json(
                "LOCAL_RUNTIME", "WEB_RESEARCH", "search-agent", 0.96, ""),
                search, fallbackCalls, new AtomicReference<>());

        AgentState result = supervisor.run(
                AgentRequest.of("s1", "当前 AgentOS 有哪些工具"),
                InvocationContext.of("supervisor-agent"), running(), events::add);

        assertThat(result.output()).isEqualTo("fallback");
        assertThat(search.calls).hasValue(0);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(events).anyMatch(event -> event.type() == AgentRunEvent.Type.ROUTE_REJECTED);
    }

    @Test
    void malformedDecisionFallsBackWithoutGuessingAgentId() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        SupervisorAgent supervisor = supervisor("search-agent", new StubRoutableAgent(true),
                fallbackCalls, new AtomicReference<>());

        AgentState result = supervisor.run(
                AgentRequest.of("s1", "search something"),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        assertThat(result.output()).isEqualTo("fallback");
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void lowConfidenceAmbiguityReturnsClarifyingQuestion() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        String question = "你指当前运行的 AgentOS，还是网上的同名产品？";
        SupervisorAgent supervisor = supervisor(json(
                "GENERAL", "GENERAL_PLANNING", "main-agent", 0.40, question),
                new StubRoutableAgent(true), fallbackCalls, new AtomicReference<>());

        AgentState result = supervisor.run(
                AgentRequest.of("s1", "AgentOS 有哪些能力"),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        assertThat(result.output()).isEqualTo(question);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void classifierReceivesConversationHistory() {
        AtomicReference<LlmRequest> seen = new AtomicReference<>();
        SupervisorAgent supervisor = supervisor(json(
                "EXTERNAL_WORLD", "WEB_RESEARCH", "search-agent", 0.9, ""),
                new StubRoutableAgent(true), new AtomicInteger(), seen);

        supervisor.run(new AgentRequest("s1", "那最新版本呢？", Map.of(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE,
                        "用户：介绍 Agno\n助手：Agno 是外部 Agent 框架")),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        assertThat(seen.get().messages()).extracting(message -> message.content())
                .containsExactly("介绍 Agno", "Agno 是外部 Agent 框架", "那最新版本呢？");
    }

    @Test
    void classifyPromptRoutesKnowledgeOrganizationTasksAwayFromSearchAgent() {
        AtomicReference<LlmRequest> seen = new AtomicReference<>();
        SupervisorAgent supervisor = supervisor(json(
                "GENERAL", "GENERAL_PLANNING", "main-agent", 0.9, ""),
                new StubRoutableAgent(true), new AtomicInteger(), seen);

        supervisor.run(AgentRequest.of("s1", "梳理微信的所有功能"),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        String instruction = seen.get().systemInstruction();
        assertThat(instruction)
                .contains("知识整理类任务")
                .contains("不得选择 search-agent")
                .contains("以模型自身知识为主体作答");
    }

    private static SupervisorAgent supervisor(
            String response,
            StubRoutableAgent search,
            AtomicInteger fallbackCalls,
            AtomicReference<LlmRequest> seen) {
        ChatClient chat = (sessionId, request) -> {
            seen.set(request);
            return response;
        };
        AgentLoop fallback = (request, context, state) -> {
            fallbackCalls.incrementAndGet();
            return state.complete("fallback");
        };
        return new SupervisorAgent(chat, Map.of(SearchAgent.ID, search), fallback,
                new ObjectMapper(), 0.75);
    }

    private static String json(
            String scope, String capability, String target, double confidence, String question) {
        return """
                {"intent":"test","scope":"%s","requiredCapabilities":["%s"],
                 "targetAgent":"%s","confidence":%s,"reason":"test route",
                 "clarifyingQuestion":"%s"}
                """.formatted(scope, capability, target, confidence, question);
    }

    private static AgentState running() {
        return AgentState.ready().startNextIteration();
    }

    private static final class StubRoutableAgent extends BaseAgent
            implements Agent, RoutableAgent {
        private final boolean accepted;
        private final AtomicInteger calls = new AtomicInteger();

        private StubRoutableAgent(boolean accepted) {
            super(SearchAgent.ID, "stub search", List.of());
            this.accepted = accepted;
        }

        @Override public Set<AgentCapability> capabilities() {
            return Set.of(AgentCapability.WEB_RESEARCH);
        }

        @Override public RouteAcceptance accepts(
                AgentRequest request, SupervisorRouteDecision decision) {
            return accepted ? RoutableAgent.super.accepts(request, decision)
                    : RouteAcceptance.reject("scope mismatch");
        }

        @Override public AgentExecutionResult run(
                AgentRequest request, InvocationContext context) {
            return AgentExecutionResult.from(run(
                    request, context, running(), AgentEventSink.NOOP), null);
        }

        @Override public AgentState run(
                AgentRequest request, InvocationContext context,
                AgentState state, AgentEventSink sink) {
            calls.incrementAndGet();
            return state.complete("searched");
        }
    }
}
