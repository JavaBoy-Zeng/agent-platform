package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runner 会话互斥、容量保护与取消令牌归属测试。 */
class AgentRunnerConcurrencyTest {

    @Test
    void evictsOldestTerminalInvocationAtConfiguredLimit() {
        AgentRunner runner = new AgentRunner(
                (request, context, running) -> running.complete(request.objective()),
                AgentEventPublisher.NOOP,
                new InMemoryCheckpointStore(),
                new InMemorySessionService(),
                AgentExecutionLimits.defaults(),
                AgentPluginManager.empty(),
                ArtifactService.NOOP,
                2,
                2);

        runner.run(AgentRequest.of("retained-1", "one"), InvocationContext.of("main-agent"));
        String firstInvocation = runner.latestInvocation("retained-1")
                .orElseThrow().invocationId();
        runner.run(AgentRequest.of("retained-2", "two"), InvocationContext.of("main-agent"));
        runner.run(AgentRequest.of("retained-3", "three"), InvocationContext.of("main-agent"));

        assertThat(runner.invocation(firstInvocation)).isEmpty();
        assertThat(runner.latestInvocation("retained-1")).isEmpty();
        // Invocation 和内存状态可以淘汰，但状态查询仍从持久化会话恢复。
        assertThat(runner.state("retained-1")).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(AgentState.Status.COMPLETED);
            assertThat(state.output()).isEqualTo("one");
        });
        assertThat(runner.latestInvocation("retained-2")).isPresent();
        assertThat(runner.latestInvocation("retained-3")).isPresent();
    }

    @Test
    void rejectsOverlappingSessionAndCapacityWithoutReplacingActiveInvocation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentLoop loop = (request, context, running) -> {
            entered.countDown();
            await(release);
            context.throwIfCancelled();
            return running.complete("done");
        };
        AgentRunner runner = new AgentRunner(
                loop,
                AgentEventPublisher.NOOP,
                new InMemoryCheckpointStore(),
                new InMemorySessionService(),
                AgentExecutionLimits.defaults(),
                AgentPluginManager.empty(),
                ArtifactService.NOOP,
                1);

        Thread worker = Thread.ofVirtual().start(() -> runner.run(
                AgentRequest.of("session-1", "first"),
                InvocationContext.of("main-agent")));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        String activeInvocationId = runner.latestInvocation("session-1")
                .orElseThrow().invocationId();

        assertThatThrownBy(() -> runner.run(
                AgentRequest.of("session-1", "second"),
                InvocationContext.of("main-agent")))
                .isInstanceOfSatisfying(AgentRunRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(AgentRunRejectedException.Reason.SESSION_BUSY));
        assertThatThrownBy(() -> runner.run(
                AgentRequest.of("session-2", "other"),
                InvocationContext.of("main-agent")))
                .isInstanceOfSatisfying(AgentRunRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(AgentRunRejectedException.Reason.CAPACITY_EXCEEDED));

        assertThat(runner.latestInvocation("session-1").orElseThrow().invocationId())
                .isEqualTo(activeInvocationId);
        assertThat(runner.latestInvocation("session-2")).isEmpty();
        assertThat(runner.cancel("session-1", "stop active")).isTrue();
        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(worker.isAlive()).isFalse();
        assertThat(runner.state("session-1").orElseThrow().status())
                .isEqualTo(AgentState.Status.CANCELLED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("interrupted");
        }
    }
}
