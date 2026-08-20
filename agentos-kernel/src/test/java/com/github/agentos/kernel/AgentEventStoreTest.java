package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EventStore 轨迹查询测试。 */
class AgentEventStoreTest {

    @Test
    void boundsEventCountsAndIndexKeys() {
        InMemoryAgentEventStore store = new InMemoryAgentEventStore(2, 2, 2, 3);
        store.append(event("event-1", "session-1", "invocation-1"));
        store.append(event("event-2", "session-1", "invocation-1"));
        store.append(event("event-3", "session-1", "invocation-1"));

        assertThat(store.findByInvocationId("invocation-1"))
                .extracting(AgentEvent::eventId)
                .containsExactly("event-2", "event-3");
        assertThat(store.findBySessionId("session-1")).hasSize(3);

        store.append(event("event-4", "session-2", "invocation-2"));
        store.append(event("event-5", "session-3", "invocation-3"));

        assertThat(store.findByInvocationId("invocation-1")).isEmpty();
        assertThat(store.findBySessionId("session-1")).isEmpty();
        assertThat(store.findByInvocationId("invocation-2")).hasSize(1);
        assertThat(store.findByInvocationId("invocation-3")).hasSize(1);
    }

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

    private static AgentEvent event(String eventId, String sessionId, String invocationId) {
        return new DefaultAgentEvent(
                eventId,
                sessionId,
                invocationId,
                "main-agent",
                Instant.now(),
                AgentEventType.AGENT_STARTED,
                eventId,
                Map.of());
    }
}
