package com.github.agentos.kernel;

/**
 * Agent 单次运行循环的执行协议。
 *
 * <p>具体 Agent 通过实现该接口接入 {@link AgentRuntime}。运行时负责会话状态管理，
 * 实现类负责规划、工具调用、记忆写入等业务编排。</p>
 */
@FunctionalInterface
public interface AgentLoop {

    /**
     * 执行一次 Agent 循环。
     *
     * @param context 本次运行的上下文
     * @param runningState 已进入运行中的状态快照
     * @return 本次运行结束后的状态，通常为完成或失败状态
     */
    AgentState run(AgentContext context, AgentState runningState);
}
