package com.github.agentos.kernel;

/**
 * Agent 单次运行循环的执行协议。
 *
 * <p>具体 Agent 通过实现该接口接入 {@link AgentRunner}。Runner 负责会话状态管理，
 * 实现类负责规划、工具调用、记忆写入等业务编排。</p>
 */
@FunctionalInterface
public interface AgentLoop {

    /**
     * 执行一次 Agent 循环。
     *
     * @param request 本次用户请求
     * @param context 本次运行的 Invocation 上下文
     * @param runningState 已进入运行中的状态快照
     * @return 本次运行结束后的状态，通常为完成或失败状态
     */
    AgentState run(AgentRequest request, InvocationContext context, AgentState runningState);

    /**
     * 执行一次 Agent 循环并向观察端持续发送运行事件。
     *
     * <p>默认实现保持现有 AgentLoop 的源代码兼容性。</p>
     */
    default AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        return run(request, context, runningState);
    }

    /**
     * 从 Checkpoint 恢复执行。实现可根据 {@code currentStepIndex} 跳过已完成步骤；
     * 默认实现保持旧 AgentLoop 兼容并重新进入普通执行入口。
     */
    default AgentState resume(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentCheckpoint checkpoint,
            PendingActionResolution resolution,
            AgentEventSink eventSink) {
        return run(request, context, runningState, eventSink);
    }

    /**
     * 在 Runner 写入 Checkpoint 前补充执行循环私有的恢复位置。
     *
     * <p>默认实现保留基础快照；Plan、React 等策略可以覆盖此方法写入当前步骤和
     * 已完成步骤，而无需让 kernel 依赖具体执行模型。</p>
     */
    default AgentCheckpoint checkpoint(
            AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
        return checkpoint;
    }

    /** 丢弃已经拒绝或无法继续的 Checkpoint 私有恢复数据。 */
    default void discard(AgentCheckpoint checkpoint) {
        // 默认循环没有额外恢复数据。
    }
}
