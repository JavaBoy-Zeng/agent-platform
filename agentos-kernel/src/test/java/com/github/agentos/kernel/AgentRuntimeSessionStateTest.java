package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 事件携带状态增量合并进会话状态的测试。 */
class AgentRuntimeSessionStateTest {

    @Test
    void mergesEventStateDeltaIntoSessionState() {
        InMemorySessionService sessions = new InMemorySessionService();
        AgentRuntime runtime = new AgentRuntime(
                (request, context, running) -> {
                    context.eventPublisher().publish(DefaultAgentEvent.of(
                            context, AgentEventType.TOOL_CALL_COMPLETED, "weather done",
                            Map.of(),
                            EventActions.stateDelta(Map.of("city", "重庆", "unit", "celsius"))));
                    return running.complete("28℃");
                },
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), sessions);

        runtime.run(AgentRequest.of("session-1", "重庆天气"), AgentContext.of("main-agent"));

        Session session = sessions.find("session-1").orElseThrow();
        assertThat(session.state().value("city")).isEqualTo("重庆");
        assertThat(session.state().value("unit")).isEqualTo("celsius");
    }

    @Test
    void terminalEventRecordsTurnLifecycleInState() {
        InMemorySessionService sessions = new InMemorySessionService();
        AgentRuntime runtime = new AgentRuntime(
                (request, context, running) -> running.complete("ok"),
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), sessions);

        runtime.run(AgentRequest.of("session-1", "第一个问题"), AgentContext.of("main-agent"));
        runtime.run(AgentRequest.of("session-1", "第二个问题"), AgentContext.of("main-agent"));

        Session session = sessions.find("session-1").orElseThrow();
        assertThat(session.state().longValue("turnCount", 0)).isEqualTo(2L);
        assertThat(session.state().stringValue("lastObjective", ""))
                .isEqualTo("第二个问题");
        assertThat(session.state().stringValue("lastStatus", ""))
                .isEqualTo("COMPLETED");
    }

    @Test
    void waitingRunDoesNotCountTurn() {
        InMemorySessionService sessions = new InMemorySessionService();
        AgentRuntime runtime = new AgentRuntime(
                (request, context, running) -> {
                    context.invocation().waitFor(new PendingAction(
                            "approval-1", PendingActionType.HUMAN_APPROVAL,
                            "审批", "等待审批", Map.of()));
                    return running.waitForAction("等待审批");
                },
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), sessions);

        runtime.run(AgentRequest.of("session-1", "高危操作"), AgentContext.of("main-agent"));

        Session session = sessions.find("session-1").orElseThrow();
        assertThat(session.state().value("turnCount")).isNull();
        assertThat(session.state().value("lastStatus")).isNull();
    }

    @Test
    void replayingEventStreamReproducesSessionState() {
        InMemorySessionService sessions = new InMemorySessionService();
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentRuntime runtime = new AgentRuntime(
                (request, context, running) -> {
                    context.eventPublisher().publish(DefaultAgentEvent.of(
                            context, AgentEventType.TOOL_CALL_COMPLETED, "tool done",
                            Map.of(), EventActions.stateDelta(Map.of("city", "重庆"))));
                    return running.complete("done");
                },
                AgentEventPublisher.NOOP, eventStore,
                new InMemoryCheckpointStore(), sessions);

        runtime.run(AgentRequest.of("session-1", "第一个问题"), AgentContext.of("main-agent"));
        runtime.run(AgentRequest.of("session-1", "第二个问题"), AgentContext.of("main-agent"));

        // 事件流重放：按时间顺序应用每条事件的 stateDelta，应得到与会话一致的最终状态。
        SessionState replayed = SessionState.empty();
        for (AgentEvent event : eventStore.findBySessionId("session-1")) {
            replayed = replayed.withDelta(event.actions().stateDelta());
        }
        Session actual = sessions.find("session-1").orElseThrow();

        assertThat(replayed.asMap()).isEqualTo(actual.state().asMap());
        assertThat(replayed.longValue("turnCount", 0)).isEqualTo(2L);
        assertThat(replayed.value("city")).isEqualTo("重庆");
    }

    @Test
    void observerFailureInSessionServiceDoesNotBreakRun() {
        SessionService broken = new SessionService() {
            @Override
            public Session getOrCreate(String sessionId, String userId) {
                throw new IllegalStateException("session service unavailable");
            }

            @Override
            public java.util.Optional<Session> find(String sessionId) {
                return java.util.Optional.empty();
            }

            @Override
            public Session applyDelta(String sessionId, Map<String, Object> delta) {
                throw new IllegalStateException("session service unavailable");
            }
        };
        AgentRuntime runtime = new AgentRuntime(
                (request, context, running) -> {
                    context.eventPublisher().publish(DefaultAgentEvent.of(
                            context, AgentEventType.TOOL_CALL_COMPLETED, "done", Map.of(),
                            EventActions.stateDelta(Map.of("k", "v"))));
                    return running.complete("ok");
                },
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), broken);

        AgentState state = runtime.run(
                AgentRequest.of("session-1", "问题"), AgentContext.of("main-agent"));

        assertThat(state.status()).isEqualTo(AgentState.Status.COMPLETED);
    }
}
