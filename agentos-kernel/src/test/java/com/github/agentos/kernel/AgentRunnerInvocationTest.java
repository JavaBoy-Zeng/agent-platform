package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Invocation 执行边界测试。 */
class AgentRunnerInvocationTest {

    @Test
    void createsDifferentInvocationIdsForTwoRunsInSameSession() {
        List<InvocationContext> contexts = new ArrayList<>();
        AgentRunner runner = new AgentRunner((request, context, running) -> {
            contexts.add(context);
            return running.complete("ok");
        });
        AgentRequest request = AgentRequest.of("same-session", "test");

        runner.run(request, InvocationContext.of("main-agent"));
        String firstId = runner.latestInvocation("same-session").orElseThrow().invocationId();
        runner.run(request, InvocationContext.of("main-agent"));
        AgentInvocation second = runner.latestInvocation("same-session").orElseThrow();

        assertThat(contexts).extracting(InvocationContext::invocationId)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
        assertThat(second.invocationId()).isNotEqualTo(firstId);
        assertThat(second.sessionId()).isEqualTo("same-session");
        assertThat(second.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(second.finishedAt()).isNotNull();
    }

    @Test
    void bindsSessionAndBudgetIntoInvocationContext() {
        InMemorySessionService sessions = new InMemorySessionService();
        sessions.applyDelta("session-1", java.util.Map.of("turnCount", 5));
        List<InvocationContext> contexts = new ArrayList<>();
        AgentExecutionLimits limits = new AgentExecutionLimits(2, 12, 12, 4);
        AgentRunner runner = new AgentRunner(
                (request, context, running) -> {
                    contexts.add(context);
                    return running.complete("ok");
                },
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), sessions, limits);

        runner.run(AgentRequest.of("session-1", "test"), InvocationContext.of("main-agent"));

        InvocationContext bound = contexts.getFirst();
        assertThat(bound.session()).isNotNull();
        assertThat(bound.session().sessionId()).isEqualTo("session-1");
        assertThat(bound.sessionState().longValue("turnCount", 0)).isEqualTo(5);
        assertThat(bound.budget()).isEqualTo(limits);
    }

    @Test
    void contextWithoutSessionExposesEmptyState() {
        InvocationContext context = InvocationContext.of("main-agent");

        assertThat(context.session()).isNull();
        assertThat(context.sessionState().asMap()).isEmpty();
        assertThat(context.budget()).isEqualTo(AgentExecutionLimits.defaults());
    }
}
