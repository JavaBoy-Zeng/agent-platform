package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行执行全部子 Agent 并合并结果的编排 Agent。
 *
 * <p>子 Agent 在虚拟线程上同时执行，共享同一运行世界；事件接收端做同步包装，
 * 保证并行发射的事件不交错损坏。合并优先级：FAILED &gt; CANCELLED &gt; WAITING &gt;
 * COMPLETED；全部成功时按子 Agent 声明顺序拼接非空输出。</p>
 */
public final class ParallelAgent extends BaseAgent {

    /**
     * 创建并行编排 Agent。
     *
     * @param id Agent 稳定唯一标识
     * @param description 能力说明
     * @param subAgents 同时执行的子 Agent
     */
    public ParallelAgent(String id, String description, List<BaseAgent> subAgents) {
        super(id, description, subAgents);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        List<BaseAgent> children = subAgents();
        if (children.isEmpty()) {
            return runningState.complete("");
        }
        AgentEventSink synchronizedSink = new SynchronizedEventSink(eventSink);
        List<AgentState> results = executeAll(children, request, context, runningState,
                synchronizedSink);
        return merge(children, results, runningState);
    }

    private List<AgentState> executeAll(
            List<BaseAgent> children,
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        List<Future<AgentState>> futures;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = children.stream()
                    .map(child -> executor.submit(
                            () -> runChild(child, request, context, runningState, eventSink)))
                    .toList();
        }
        List<AgentState> results = new ArrayList<>(futures.size());
        for (Future<AgentState> future : futures) {
            results.add(await(future, runningState));
        }
        return List.copyOf(results);
    }

    private static AgentState await(Future<AgentState> future, AgentState runningState) {
        try {
            return Objects.requireNonNull(future.get(), "child agent returned null state");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return runningState.cancel("parallel child interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            String message = cause == null
                    ? exception.getClass().getSimpleName()
                    : cause.getMessage() == null ? cause.getClass().getSimpleName()
                            : cause.getMessage();
            return runningState.fail(message);
        }
    }

    private static AgentState merge(
            List<BaseAgent> children, List<AgentState> results, AgentState runningState) {
        AgentState failure = firstWithStatus(results, AgentState.Status.FAILED);
        if (failure != null) {
            return failure;
        }
        AgentState cancelled = firstWithStatus(results, AgentState.Status.CANCELLED);
        if (cancelled != null) {
            return cancelled;
        }
        AgentState waiting = firstWithStatus(results, AgentState.Status.WAITING);
        if (waiting != null) {
            return waiting;
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            String childOutput = results.get(i).output();
            if (!childOutput.isBlank()) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append(children.get(i).id()).append(": ").append(childOutput);
            }
        }
        return runningState.complete(output.toString());
    }

    private static AgentState firstWithStatus(List<AgentState> results, AgentState.Status status) {
        return results.stream().filter(state -> state.status() == status).findFirst().orElse(null);
    }

    /** 并行子 Agent 共享的事件接收端包装，串行化并发 emit。 */
    private record SynchronizedEventSink(AgentEventSink delegate) implements AgentEventSink {

        @Override
        public synchronized void emit(com.github.agentos.kernel.AgentRunEvent event) {
            delegate.emit(event);
        }
    }
}
