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
     * @param request 本次用户请求
     * @param context 本次身份和任务上下文
     * @param runningState 已进入运行中的状态快照
     * @return 本次运行结束后的状态，通常为完成或失败状态
     */
    AgentState run(AgentRequest request, AgentContext context, AgentState runningState);

    /**
     * 执行一次 Agent 循环并向观察端持续发送运行事件。
     *
     * <p>默认实现保持现有 AgentLoop 的源代码兼容性。</p>
     */
    default AgentState run(
            AgentRequest request,
            AgentContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        return run(request, context, runningState);
    }
}
