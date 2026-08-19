package com.github.agentos.tool.api;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.SessionState;

import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用可访问的完整运行世界。
 *
 * <p>工具执行时不再只收到裸参数，而是同时拿到 Invocation 上下文（身份、会话、
 * 预算、事件发布器）、计划步骤定位、会话状态视图与解析后的工具自身。
 * 文件类工具可以读取 {@link #state()}，记忆类工具可以借助 {@link #invocation()}
 * 定位会话，HITL 类工具通过 {@link ToolActions} 返回审批请求，无需各自注入 Bean。</p>
 *
 * @param request 本次用户请求
 * @param invocation 本次运行的 Invocation 上下文
 * @param planId 所属计划标识，非计划执行时为空字符串
 * @param stepId 所属步骤标识，非计划执行时为空字符串
 * @param executionLimits 剩余运行预算
 * @param state 调用方提供的会话状态视图
 * @param tool 本次解析到的工具自身
 */
public record ToolContext(
        AgentRequest request,
        InvocationContext invocation,
        String planId,
        String stepId,
        AgentExecutionLimits executionLimits,
        Map<String, Object> state,
        AgentTool tool) {

    /** 校验并复制会话状态，保证工具看到的快照不可变。 */
    public ToolContext {
        request = Objects.requireNonNull(request, "request must not be null");
        invocation = Objects.requireNonNull(invocation, "invocation must not be null");
        planId = planId == null ? "" : planId.trim();
        stepId = stepId == null ? "" : stepId.trim();
        executionLimits = Objects.requireNonNull(
                executionLimits, "executionLimits must not be null");
        state = state == null ? Map.of() : Map.copyOf(state);
        tool = Objects.requireNonNull(tool, "tool must not be null");
    }

    /** 返回本次工具调用所属的会话标识。 */
    public String sessionId() {
        return request.sessionId();
    }

    /** 返回触发本次工具调用的 Agent 标识。 */
    public String agentId() {
        return invocation.agentId();
    }

    /** 返回本次运行的 Invocation 标识，未进入 Runner 时为空字符串。 */
    public String invocationId() {
        return invocation.invocationId();
    }

    /** 返回结构化会话状态；会话未注入时返回空状态。 */
    public SessionState sessionState() {
        return invocation.sessionState();
    }
}
