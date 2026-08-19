package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 领域事件发布与 Invocation 关联测试。 */
class AgentEventPublisherTest {

    @Test
    void publishesLifecycleEventsWithSameInvocation() {
        List<AgentEvent> events = new ArrayList<>();
        InMemoryAgentEventPublisher publisher = new InMemoryAgentEventPublisher(List.of(events::add));
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
        AgentRunner runner = new AgentRunner(loop, publisher);

        runner.run(AgentRequest.of("session-1", "test"), InvocationContext.of("main-agent"));

        assertThat(events).extracting(AgentEvent::type).containsExactly(
                AgentEventType.AGENT_STARTED,
                AgentEventType.PLAN_CREATED,
                AgentEventType.AGENT_COMPLETED);
        assertThat(events).extracting(AgentEvent::invocationId).doesNotContain("");
        assertThat(events.stream().map(AgentEvent::invocationId).distinct()).hasSize(1);
        assertThat(events).extracting(AgentEvent::sessionId).containsOnly("session-1");
        assertThat(events).extracting(AgentEvent::agentId).containsOnly("main-agent");
    }
}
