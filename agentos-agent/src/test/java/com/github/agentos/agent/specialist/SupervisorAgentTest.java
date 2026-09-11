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
    void approvalModesDispatchSpecialistsToTheirOwnToolBoundary() {
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

        assertThat(result.output()).isEqualTo("searched");
        assertThat(search.calls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
        assertThat(events).noneMatch(event -> event.type() == AgentRunEvent.Type.ROUTE_REJECTED);
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
                "GENERAL", "GENERAL_PLANNING", "plan-execute-agent", 0.40, question),
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
                "GENERAL", "GENERAL_PLANNING", "plan-execute-agent", 0.9, ""),
                new StubRoutableAgent(true), new AtomicInteger(), seen);

        supervisor.run(AgentRequest.of("s1", "梳理微信的所有功能"),
                InvocationContext.of("supervisor-agent"), running(), event -> { });

        String instruction = seen.get().systemInstruction();
        assertThat(instruction)
                .contains("知识整理类任务")
                .contains("不得选择 search-agent")
                .contains("以模型自身知识为主体作答");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "plan-execute-agent,react-agent", "react-agent,plan-execute-agent"})
    void routesBothExecutionAgentsIndependentlyOfDefault(String target, String defaultId) {
        AtomicReference<LlmRequest> seen = new AtomicReference<>();
        AgentLoop main = (request, context, state) -> state.complete(context.agentId());
        AgentLoop react = (request, context, state) -> state.complete(context.agentId());
        SupervisorAgent supervisor = new SupervisorAgent((session, request) -> {
            seen.set(request);
            return json("GENERAL", "GENERAL_PLANNING", target, 0.95, "");
        }, Map.of(), Map.of("plan-execute-agent", main, "react-agent", react), defaultId,
                new ObjectMapper(), 0.8);
        List<AgentRunEvent> events = new ArrayList<>();
        assertThat(supervisor.run(AgentRequest.of("s1", "task"),
                InvocationContext.of(SupervisorAgent.ID), running(), events::add).output()).isEqualTo(target);
        assertThat(events).filteredOn(e -> e.type() == AgentRunEvent.Type.ROUTE_DECIDED)
                .singleElement().satisfies(e -> assertThat(e.data()).containsEntry("targetAgent", target));
        assertThat(seen.get().systemInstruction())
                .contains("实际可用 Agent：plan-execute-agent、react-agent。", "默认 Agent：" + defaultId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plan-execute-agent", "react-agent"})
    void invalidDecisionUsesConfiguredDefault(String defaultId) {
        AgentLoop main = (request, context, state) -> state.complete("plan-execute-agent");
        AgentLoop react = (request, context, state) -> state.complete("react-agent");
        SupervisorAgent supervisor = new SupervisorAgent((session, request) -> "invalid",
                Map.of(), Map.of("plan-execute-agent", main, "react-agent", react), defaultId,
                new ObjectMapper(), 0.75);
        assertThat(supervisor.run(AgentRequest.of("s1", "task"),
                InvocationContext.of(SupervisorAgent.ID), running()).output()).isEqualTo(defaultId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plan-execute-agent", "react-agent"})
    void checkpointRestoresSelectedAgentWithoutReclassification(String target) {
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        AgentLoop selected = new AgentLoop() {
            @Override public AgentState run(AgentRequest request, InvocationContext context, AgentState state) {
                assertThat(context.agentId()).isEqualTo(target);
                return state.waitForAction("approval");
            }
            @Override public AgentState resume(AgentRequest request, InvocationContext context, AgentState state,
                    com.github.agentos.kernel.AgentCheckpoint checkpoint,
                    com.github.agentos.kernel.PendingActionResolution resolution, AgentEventSink sink) {
                assertThat(context.agentId()).isEqualTo(target);
                return resumes.incrementAndGet() == 1 ? state.waitForAction("second approval")
                        : state.complete("resumed");
            }
            @Override public void discard(com.github.agentos.kernel.AgentCheckpoint checkpoint) {
                discards.incrementAndGet();
            }
        };
        AgentLoop other = (request, context, state) -> { throw new AssertionError("wrong agent"); };
        var request = AgentRequest.of("s1", "task");
        var context = InvocationContext.of(SupervisorAgent.ID).withInvocation(
                new com.github.agentos.kernel.AgentInvocation(
                        "i1", "s1", SupervisorAgent.ID, "", java.time.Instant.now()));
        String defaultId = target.equals("plan-execute-agent") ? "react-agent" : "plan-execute-agent";
        var agents = Map.of(target, selected, defaultId, other);
        var supervisor = new SupervisorAgent((session, input) ->
                json("GENERAL", "GENERAL_PLANNING", target, 0.95, ""),
                Map.of(), agents, defaultId, new ObjectMapper(), 0.75);
        supervisor.run(request, context, running(), AgentEventSink.NOOP);
        var base = new com.github.agentos.kernel.AgentCheckpoint("s1", "i1", SupervisorAgent.ID, "", "team", "user",
                "task", "", "", 0, List.of(), Map.of("existing", "value"), null,
                new com.github.agentos.kernel.ExecutionCounters(0, 0, 0, 0),
                com.github.agentos.kernel.AgentRunStatus.WAITING, java.time.Instant.now());
        var saved = supervisor.checkpoint(request, context, base);
        assertThat(saved.agentId()).isEqualTo(target);
        assertThat(saved.state()).containsEntry("existing", "value");
        var restarted = new SupervisorAgent((session, input) -> {
            throw new AssertionError("resume must not classify again");
        }, Map.of(), agents, defaultId, new ObjectMapper(), 0.75);
        assertThat(restarted.resume(request, context, running(), saved,
                com.github.agentos.kernel.PendingActionResolution.approved("p1"),
                AgentEventSink.NOOP).status()).isEqualTo(AgentState.Status.WAITING);
        var second = restarted.checkpoint(request, context, base);
        assertThat(second.agentId()).isEqualTo(target);
        assertThat(restarted.resume(request, context, running(), second,
                com.github.agentos.kernel.PendingActionResolution.approved("p2"),
                AgentEventSink.NOOP).output()).isEqualTo("resumed");
        restarted.discard(second);
        assertThat(discards).hasValue(1);
        assertThat(resumes).hasValue(2);
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
