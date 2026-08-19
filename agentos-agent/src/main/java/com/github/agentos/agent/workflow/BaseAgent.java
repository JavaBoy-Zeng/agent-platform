package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;

import java.util.List;

/**
 * 所有可组合 Agent 的抽象基类。
 *
 * <p>BaseAgent 只回答“我是谁、有哪些子 Agent、如何运行”，本身不绑定 LLM：
 * {@link SequentialAgent}、{@link ParallelAgent}、{@link LoopAgent} 等纯编排 Agent
 * 与 LLM 驱动的 Agent 共享同一抽象。实现类通过实现 {@link AgentLoop} 的
 * {@code run} 入口接入 {@code AgentRunner}。</p>
 *
 * <p>子 Agent 执行时通过 {@link #runChild} 派生以子 Agent 标识为中心的
 * {@link InvocationContext}，其余运行世界（会话、预算、Invocation、事件发布器）
 * 原样共享。</p>
 */
public abstract class BaseAgent implements AgentLoop {

    private final String id;
    private final String description;
    private final List<BaseAgent> subAgents;

    /**
     * 创建并校验 Agent 定义。
     *
     * @param id Agent 稳定唯一标识
     * @param description 面向使用者和调度策略的能力说明
     * @param subAgents 按声明顺序参与的子 Agent 列表
     * @throws IllegalArgumentException 当标识或说明为空时抛出
     */
    protected BaseAgent(String id, String description, List<BaseAgent> subAgents) {
        this.id = requireText(id, "id");
        this.description = requireText(description, "description");
        this.subAgents = subAgents == null ? List.of() : List.copyOf(subAgents);
    }

    /** 返回 Agent 的稳定唯一标识。 */
    public String id() {
        return id;
    }

    /** 返回面向使用者和调度策略的能力说明。 */
    public String description() {
        return description;
    }

    /** 返回按声明顺序参与的子 Agent 列表。 */
    public List<BaseAgent> subAgents() {
        return subAgents;
    }

    /**
     * 3 参数入口的适配实现：无观察端时以 {@link AgentEventSink#NOOP} 运行。
     */
    @Override
    public final AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    /**
     * 执行一次 Agent 循环并向观察端持续发送运行事件。
     *
     * @param request 本次用户请求
     * @param context 本次运行的 Invocation 上下文
     * @param runningState 已进入运行中的状态快照
     * @param eventSink 运行事件接收端
     * @return 本次运行结束后的状态
     */
    @Override
    public abstract AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink);

    /**
     * 以当前上下文派生子 Agent 作用域并执行一次子 Agent。
     *
     * @param child 要执行的子 Agent
     * @param request 本次用户请求
     * @param context 当前 Invocation 上下文
     * @param runningState 当前运行状态快照
     * @param eventSink 运行事件接收端
     * @return 子 Agent 运行结束后的状态
     */
    protected final AgentState runChild(
            BaseAgent child,
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        return child.run(request, context.withAgentId(child.id()), runningState, eventSink);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
