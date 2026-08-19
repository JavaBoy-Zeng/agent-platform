package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 把 {@link BaseAgent} 适配为 {@link AgentTool} 的桥接器。
 *
 * <p>对规划器和 ToolDispatcher 而言，被包装的 Agent 只是注册表里的一个工具：
 * 调度机制无需感知能力来自 Java 函数还是 Agent。工具名复用 Agent 标识，
 * 必填参数 {@code objective} 作为子 Agent 的任务目标，可选参数 {@code sessionId}
 * 用于事件与状态归属（缺省自动生成）。</p>
 *
 * <p>子 Agent 在工具调用内直接执行、不经 Runner 边界，因此无法挂起等待审批；
 * 若子 Agent 返回 WAITING，本适配器按失败处理并说明原因。</p>
 */
public final class AgentToolAdapter implements AgentTool {

    /** 子 Agent 任务目标参数名。 */
    public static final String OBJECTIVE_PARAMETER = "objective";

    /** 可选的会话归属参数名。 */
    public static final String SESSION_ID_PARAMETER = "sessionId";

    private final BaseAgent agent;

    /** 创建以工具形态暴露指定 Agent 的适配器。 */
    public AgentToolAdapter(BaseAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
    }

    /** 返回被包装的 Agent。 */
    public BaseAgent agent() {
        return agent;
    }

    @Override
    public String name() {
        return agent.id();
    }

    @Override
    public String description() {
        return agent.description();
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        OBJECTIVE_PARAMETER, ToolParameter.ValueType.STRING,
                        "Task objective handed to the wrapped agent", true),
                new ToolParameter(
                        SESSION_ID_PARAMETER, ToolParameter.ValueType.STRING,
                        "Optional session id used for event and state attribution", false));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object objective = call.arguments().get(OBJECTIVE_PARAMETER);
        if (!(objective instanceof String text) || text.isBlank()) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT,
                    OBJECTIVE_PARAMETER + " must be a non-blank string");
        }
        String sessionId = call.arguments().get(SESSION_ID_PARAMETER) instanceof String value
                && !value.isBlank() ? value
                        : "agent-tool-" + UUID.randomUUID();
        AgentRequest request = AgentRequest.of(sessionId, text);
        InvocationContext context = InvocationContext.of(agent.id());
        AgentState state = agent.run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
        return switch (state.status()) {
            case COMPLETED -> ToolResult.success(state.output());
            case WAITING -> ToolResult.failure(
                    ToolFailureType.PERMISSION_DENIED,
                    "wrapped agent is waiting for approval which cannot be resumed"
                            + " inside a tool call");
            default -> ToolResult.failure(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    state.error().isBlank() ? state.status().name() : state.error());
        };
    }
}
