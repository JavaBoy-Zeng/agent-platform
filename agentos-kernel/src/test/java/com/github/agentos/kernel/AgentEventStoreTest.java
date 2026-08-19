package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EventStore 轨迹查询测试。 */
class AgentEventStoreTest {

    @Test
    void restoresCompleteInvocationTraceAndAggregatesSessionRuns() {
        InMemoryAgentEventStore store = new InMemoryAgentEventStore();
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(AgentRequest request, InvocationContext context, AgentState running) {
                return running.complete("done");
            }

            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState running,
                    AgentEventSink sink) {
                sink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.PLAN_CREATED, request.sessionId(), "plan",
                        java.util.Map.of()));
                return running.complete("done");
            }
        };
        AgentRunner runner = new AgentRunner(loop, AgentEventPublisher.NOOP, store);
        AgentRequest request = AgentRequest.of("same-session", "test");

        runner.run(request, InvocationContext.of("main-agent"));
        String firstId = runner.latestInvocation("same-session").orElseThrow().invocationId();
        runner.run(request, InvocationContext.of("main-agent"));
        String secondId = runner.latestInvocation("same-session").orElseThrow().invocationId();

        assertThat(store.findByInvocationId(firstId)).extracting(AgentEvent::type)
                .containsExactly(
                        AgentEventType.AGENT_STARTED,
                        AgentEventType.PLAN_CREATED,
                        AgentEventType.AGENT_COMPLETED);
        assertThat(store.findByInvocationId(secondId)).hasSize(3);
        assertThat(store.findBySessionId("same-session")).hasSize(6);
        assertThat(store.findBySessionId("same-session")).extracting(AgentEvent::invocationId)
                .containsExactly(firstId, firstId, firstId, secondId, secondId, secondId);
        assertThat(store.findByInvocationId("missing")).isEqualTo(List.of());
    }
}
