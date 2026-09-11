package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Runner 协作式取消端到端测试：令牌传播、终态收敛与插件钩子触发。 */
class AgentRunnerCancellationTest {

    @Test
    void cancelReturnsFalseWhenSessionHasNoActiveInvocation() {
        AgentRunner runner = new AgentRunner((request, context, running) -> running.complete("ok"));

        assertThat(runner.cancel("missing-session", "nope")).isFalse();

        runner.run(AgentRequest.of("session-1", "objective"), InvocationContext.of("plan-execute-agent"));

        assertThat(runner.cancel("session-1", "too late")).isFalse();
    }

    @Test
    void tokenCancellationConvergesToCancelledStateAndTriggersPluginHooks() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin();
        CountDownLatch enteredLoop = new CountDownLatch(1);
        CountDownLatch cancelDone = new CountDownLatch(1);
        AtomicBoolean tokenVisibleInLoop = new AtomicBoolean(true);
        AgentRunner runner = runnerWithPlugin(plugin, enteredLoop, cancelDone, tokenVisibleInLoop);

        Thread worker = new Thread(() -> runner.run(
                AgentRequest.of("session-cancel", "objective"),
                InvocationContext.of("plan-execute-agent")));
        worker.start();
        assertThat(enteredLoop.await(5, TimeUnit.SECONDS)).isTrue();

        boolean accepted = runner.cancel("session-cancel", "user requested");
        assertThat(accepted).isTrue();
        cancelDone.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();

        AgentState result = runner.state("session-cancel").orElseThrow();
        assertThat(result.status()).isEqualTo(AgentState.Status.CANCELLED);
        assertThat(result.error()).isEqualTo("user requested");
        assertThat(tokenVisibleInLoop.get()).isTrue();
        assertThat(runner.cancel("session-cancel", "again")).isFalse();

        AgentInvocation invocation = runner.latestInvocation("session-cancel").orElseThrow();
        assertThat(invocation.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(invocation.finishedAt()).isNotNull();

        assertThat(plugin.calls).containsSubsequence("beforeRun", "afterRun:CANCELLED");
        assertThat(plugin.calls).doesNotContain("onRunError");
    }

    @Test
    void threadInterruptChannelConvergesToCancelledState() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin();
        CountDownLatch enteredLoop = new CountDownLatch(1);
        AtomicBoolean tokenVisible = new AtomicBoolean(false);
        AgentRunner runner = runnerWithPlugin(
                plugin, enteredLoop, new CountDownLatch(1), tokenVisible);

        Thread worker = new Thread(() -> runner.run(
                AgentRequest.of("session-interrupt", "objective"),
                InvocationContext.of("plan-execute-agent")));
        worker.start();
        assertThat(enteredLoop.await(5, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();

        AgentState result = runner.state("session-interrupt").orElseThrow();
        assertThat(result.status()).isEqualTo(AgentState.Status.CANCELLED);
        assertThat(result.error()).isEqualTo("interrupted while waiting");
        assertThat(plugin.calls).contains("afterRun:CANCELLED");
        assertThat(plugin.calls).doesNotContain("onRunError");
    }

    @Test
    void normalRunKeepsTokenActiveAndFiresCompletionHooks() {
        RecordingPlugin plugin = new RecordingPlugin();
        AgentRunner runner = new AgentRunner(
                (request, context, running) -> {
                    assertThat(context.isCancelled()).isFalse();
                    context.throwIfCancelled();
                    return running.complete("done");
                },
                AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), new InMemorySessionService(),
                AgentExecutionLimits.defaults(), AgentPluginManager.of(plugin));

        AgentState result = runner.run(
                AgentRequest.of("session-ok", "objective"), InvocationContext.of("plan-execute-agent"));

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(plugin.calls).containsSubsequence("beforeRun", "afterRun:COMPLETED");
        assertThat(plugin.calls).doesNotContain("onRunError");
    }

    private AgentRunner runnerWithPlugin(
            AgentPlugin plugin, CountDownLatch enteredLoop, CountDownLatch cancelDone,
            AtomicBoolean tokenVisible) {
        AgentLoop loop = (request, context, running) -> {
            enteredLoop.countDown();
            await(cancelDone);
            // 协作点：模拟计划步骤边界检查令牌。
            tokenVisible.set(context.isCancelled());
            context.throwIfCancelled();
            return running.complete("unreachable");
        };
        return new AgentRunner(
                loop, AgentEventPublisher.NOOP, new InMemoryAgentEventStore(),
                new InMemoryCheckpointStore(), new InMemorySessionService(),
                AgentExecutionLimits.defaults(), AgentPluginManager.of(plugin));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            // 模拟阻塞 I/O 包装层：中断信号转换为取消异常上抛。
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted while waiting");
        }
    }

    private static final class RecordingPlugin implements AgentPlugin {
        final List<String> calls = new ArrayList<>();

        @Override
        public void beforeRun(AgentRequest request, InvocationContext context) {
            calls.add("beforeRun");
        }

        @Override
        public void afterRun(
                AgentRequest request, InvocationContext context, AgentState result) {
            calls.add("afterRun:" + result.status());
        }

        @Override
        public void onRunError(
                AgentRequest request, InvocationContext context, Exception error) {
            calls.add("onRunError");
        }
    }
}
