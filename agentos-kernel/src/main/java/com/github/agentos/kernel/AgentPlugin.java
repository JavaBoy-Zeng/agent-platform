package com.github.agentos.kernel;

/**
 * Agent 运行期的横切能力插件。
 *
 * <p>插件由 {@link AgentPluginManager} 统一装配，在 Runner 执行边界与用量回调点
 * 被触发，用于记账、追踪、日志、评估等横切关注点，避免这些逻辑膨胀进
 * {@link AgentRunner} 本体。所有钩子都在安全边界内调用：单个插件抛出的异常
 * 会被隔离，不会中断执行链。</p>
 *
 * <p>钩子触发时机：</p>
 * <ul>
 *   <li>{@code beforeRun/afterRun/onRunError}：一次 Invocation 的执行边界
 *      （含审批恢复的再次执行）</li>
 *   <li>{@code onModelUsage}：每次模型调用成功后（规划与直答路径共用）</li>
 *   <li>{@code onEvent}：领域事件发布时（含计划、步骤、工具、模型细粒度事件）</li>
 * </ul>
 */
public interface AgentPlugin {

    /** 插件名，用于日志与调试；默认为实现类简单名。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 一次执行开始前触发；此时 Invocation 已绑定但循环尚未开始。 */
    default void beforeRun(AgentRequest request, InvocationContext context) {
    }

    /** 一次执行到达终态后触发（COMPLETED/FAILED/CANCELLED/WAITING）。 */
    default void afterRun(AgentRequest request, InvocationContext context, AgentState result) {
    }

    /** 执行链抛出异常时触发；异常仍按原有语义向上转换。 */
    default void onRunError(AgentRequest request, InvocationContext context, Exception error) {
    }

    /** 一次模型调用成功后触发，携带 token 用量。 */
    default void onModelUsage(String sessionId, ModelUsage usage) {
    }

    /** 领域事件发布时触发；事件为不可变快照，插件不得修改执行流。 */
    default void onEvent(AgentEvent event) {
    }
}
