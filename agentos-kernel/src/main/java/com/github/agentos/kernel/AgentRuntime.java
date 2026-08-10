package com.github.agentos.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 面向会话的 Agent 运行入口。
 *
 * <p>运行时按 {@code sessionId} 保存最新状态，并通过并发 Map 的原子计算保证同一会话的
 * 状态更新串行执行。业务循环抛出的运行时异常会被转换为失败状态，避免异常越过运行边界。</p>
 */
public final class AgentRuntime {

    private final AgentLoop agentLoop;
    private final ConcurrentMap<String, AgentState> states = new ConcurrentHashMap<>();

    /**
     * 创建 Agent 运行时。
     *
     * @param agentLoop 实际执行 Agent 业务逻辑的循环
     * @throws NullPointerException 当执行循环为 {@code null} 时抛出
     */
    public AgentRuntime(AgentLoop agentLoop) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop must not be null");
    }

    /**
     * 在指定上下文中执行一次 Agent 循环并保存最终状态。
     *
     * @param context 本次运行上下文
     * @return 执行结束后的状态快照
     * @throws NullPointerException 当上下文为 {@code null} 时抛出
     */
    public AgentState run(AgentContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return states.compute(context.sessionId(), (sessionId, previous) -> {
            AgentState running = (previous == null ? AgentState.ready() : previous).startNextIteration();
            try {
                AgentState result = Objects.requireNonNull(
                        agentLoop.run(context, running), "agentLoop returned null state");
                if (result.status() == AgentState.Status.RUNNING) {
                    return result.fail("agent loop finished without a terminal state");
                }
                return result;
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                return running.fail(message);
            }
        });
    }

    /**
     * 查询指定会话最新的运行状态。
     *
     * @param sessionId 会话标识
     * @return 状态存在时返回包含状态的 {@link Optional}，否则返回空值
     */
    public Optional<AgentState> state(String sessionId) {
        return Optional.ofNullable(states.get(sessionId));
    }
}
