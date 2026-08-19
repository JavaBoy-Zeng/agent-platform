package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 串行、并行与循环编排 Agent 的行为测试。 */
class WorkflowAgentsTest {

    private static final AgentRequest REQUEST = AgentRequest.of("session-1", "objective");
    private static final AgentState RUNNING = AgentState.ready().startNextIteration();

    @Test
    void sequentialRunsChildrenInOrderAndReturnsLastOutput() {
        AtomicInteger order = new AtomicInteger();
        BaseAgent first = scripted("first", state -> {
            order.incrementAndGet();
            return state.complete("first-output");
        });
        BaseAgent second = scripted("second", state -> {
            order.incrementAndGet();
            return state.complete("second-output");
        });
        SequentialAgent agent = new SequentialAgent(
                "pipeline", "sequential pipeline", List.of(first, second));

        AgentState result = agent.run(REQUEST, InvocationContext.of("pipeline"),
                RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("second-output");
        assertThat(order).hasValue(2);
    }

    @Test
    void sequentialShortCircuitsOnFailure() {
        BaseAgent failing = scripted("failing", state -> state.fail("boom"));
        CountingAgent untouched = new CountingAgent("untouched");

        AgentState result = new SequentialAgent(
                "pipeline", "sequential pipeline", List.of(failing, untouched))
                .run(REQUEST, InvocationContext.of("pipeline"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).isEqualTo("boom");
        assertThat(untouched.executions).hasValue(0);
    }

    @Test
    void sequentialKeepsEarlierOutputWhenLastChildIsEmpty() {
        BaseAgent first = scripted("first", state -> state.complete("kept"));
        BaseAgent empty = scripted("empty", state -> state.complete(""));

        AgentState result = new SequentialAgent(
                "pipeline", "sequential pipeline", List.of(first, empty))
                .run(REQUEST, InvocationContext.of("pipeline"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.output()).isEqualTo("kept");
    }

    @Test
    void parallelRunsAllChildrenAndMergesOutputs() {
        CountingAgent first = new CountingAgent("alpha", "alpha-output");
        CountingAgent second = new CountingAgent("beta", "beta-output");

        AgentState result = new ParallelAgent(
                "fanout", "parallel fan-out", List.of(first, second))
                .run(REQUEST, InvocationContext.of("fanout"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("alpha: alpha-output\nbeta: beta-output");
        assertThat(first.executions).hasValue(1);
        assertThat(second.executions).hasValue(1);
    }

    @Test
    void parallelFailureWinsOverOtherStatuses() {
        BaseAgent ok = scripted("ok", state -> state.complete("fine"));
        BaseAgent failing = scripted("failing", state -> state.fail("boom"));

        AgentState result = new ParallelAgent(
                "fanout", "parallel fan-out", List.of(ok, failing))
                .run(REQUEST, InvocationContext.of("fanout"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).isEqualTo("boom");
    }

    @Test
    void loopStopsOnEscalateOutput() {
        BaseAgent worker = scripted("worker", new ArrayDeque<>(List.of(
                state -> state.complete("round one"),
                state -> state.complete("round two"),
                state -> state.complete(LoopAgent.ESCALATE_PREFIX + " final answer"))));

        AgentState result = new LoopAgent(
                "looper", "loop worker", List.of(worker), 10)
                .run(REQUEST, InvocationContext.of("looper"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("final answer");
    }

    @Test
    void loopCompletesAtMaxIterations() {
        CountingAgent worker = new CountingAgent("worker", "same");

        AgentState result = new LoopAgent(
                "looper", "loop worker", List.of(worker), 3)
                .run(REQUEST, InvocationContext.of("looper"), RUNNING, AgentEventSink.NOOP);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("same");
        assertThat(worker.executions).hasValue(3);
    }

    @Test
    void loopRejectsNonPositiveIterations() {
        assertThatThrownBy(() -> new LoopAgent("looper", "loop", List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void childContextCarriesChildAgentId() {
        ConcurrentLinkedQueue<String> observedAgentIds = new ConcurrentLinkedQueue<>();
        BaseAgent child = new BaseAgent("child-a", "observing child", List.of()) {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context,
                    AgentState runningState, AgentEventSink eventSink) {
                observedAgentIds.add(context.agentId());
                return runningState.complete("ok");
            }
        };
        new SequentialAgent("parent", "parent", List.of(child))
                .run(REQUEST, InvocationContext.of("parent"), RUNNING, AgentEventSink.NOOP);

        assertThat(observedAgentIds).containsExactly("child-a");
    }

    private static BaseAgent scripted(String id, Function<AgentState, AgentState> behavior) {
        return new BaseAgent(id, "scripted " + id, List.of()) {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context,
                    AgentState runningState, AgentEventSink eventSink) {
                return behavior.apply(runningState);
            }
        };
    }

    private static BaseAgent scripted(String id, Deque<Function<AgentState, AgentState>> script) {
        return new BaseAgent(id, "scripted " + id, List.of()) {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context,
                    AgentState runningState, AgentEventSink eventSink) {
                return script.poll().apply(runningState);
            }
        };
    }

    /** 统计执行次数、每次返回固定输出的桩 Agent。 */
    private static final class CountingAgent extends BaseAgent {
        private final AtomicInteger executions = new AtomicInteger();
        private final String output;

        CountingAgent(String id) {
            this(id, id + "-output");
        }

        CountingAgent(String id, String output) {
            super(id, "counting " + id, List.of());
            this.output = output;
        }

        @Override
        public AgentState run(
                AgentRequest request, InvocationContext context,
                AgentState runningState, AgentEventSink eventSink) {
            executions.incrementAndGet();
            return runningState.complete(output);
        }
    }
}
