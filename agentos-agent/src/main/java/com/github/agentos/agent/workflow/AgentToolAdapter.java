package com.github.agentos.agent.workflow;

import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>子代理真隔离</b>：execute 构造独立的 {@link AgentInvocation}（独立
 * invocationId 与计数器），子 Agent 的工具调用/模型调用计数不污染父的预算；
 * 子 Agent 的多轮 LLM 对话作为局部变量天然不进入父的 messages；
 * 取消令牌级联父的 cancellation（父取消时子自动取消）；子 Agent 的运行事件
 * 经 {@link ForwardingAgentEventSink} 转发到父的事件流，UI 可观测子 Agent
 * 的执行过程。</p>
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
    private final RiskLevel riskLevel;

    /** 创建以低风险工具形态暴露指定 Agent 的适配器。 */
    public AgentToolAdapter(BaseAgent agent) {
        this(agent, RiskLevel.LOW);
    }

    /** 创建以指定风险等级暴露指定 Agent 的适配器。 */
    public AgentToolAdapter(BaseAgent agent, RiskLevel riskLevel) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
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
    public RiskLevel riskLevel() {
        return riskLevel;
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
    public ToolResult execute(ToolContext toolContext, ToolCall call) {
        Object objective = call.arguments().get(OBJECTIVE_PARAMETER);
        if (!(objective instanceof String text) || text.isBlank()) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT,
                    OBJECTIVE_PARAMETER + " must be a non-blank string");
        }
        String sessionId = call.arguments().get(SESSION_ID_PARAMETER) instanceof String value
                && !value.isBlank() ? value
                        : "agent-tool-" + UUID.randomUUID();

        InvocationContext parentContext = toolContext.invocation();
        String parentInvocationId = parentContext.invocationId();
        String parentSessionId = parentInvocationId.isBlank()
                ? sessionId : parentContext.sessionId();
        // 独立 invocationId 让子 Agent 有独立运行身份，便于事件追踪与日志关联。
        String subInvocationId = parentInvocationId.isBlank()
                ? "sub-" + UUID.randomUUID()
                : parentInvocationId + "/sub/" + agent.id() + "/" + UUID.randomUUID();

        // 独立的 Invocation：子 Agent 的计数器（modelCalls/toolCalls）独立累计，
        // 不污染父的预算与运行状态。
        AgentInvocation subInvocation = new AgentInvocation(
                subInvocationId, parentSessionId, agent.id(),
                parentContext.taskId(), Instant.now());
        subInvocation.start();

        // 子上下文：换 agentId + 独立 invocation，但保留父的 cancellation（级联取消）、
        // eventPublisher、artifacts 与 budget。
        InvocationContext subContext = parentContext
                .withAgentId(agent.id())
                .withInvocation(subInvocation);

        // 子 Agent 运行事件经转发 sink 发到父的事件流，UI 可见子 Agent 的执行过程。
        // 用 parentContext.withAgentId(agent.id()) 发事件：事件归属父的 invocationId，
        // 但 agentId 标识子 Agent，data 中额外带 subagentId/subInvocationId 区分。
        InvocationContext forwardingContext = parentContext.withAgentId(agent.id());
        AgentEventSink forwardingSink = new ForwardingAgentEventSink(
                forwardingContext, agent.id(), subInvocationId);

        AgentRequest request = new AgentRequest(
                sessionId, text, toolContext.request().attributes());
        AgentState state = agent.run(
                request, subContext, AgentState.ready().startNextIteration(), forwardingSink);
        subInvocation.finish(state);

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

    /**
     * 把子 Agent 的 {@link AgentRunEvent} 转发为 {@link DefaultAgentEvent}，
     * 经父的事件发布器发出，让 UI 与日志可观测子 Agent 的执行过程。
     *
     * <p>事件归属父的 invocationId（通过 forwardingContext），agentId 标识子 Agent，
     * data 中额外携带 {@code subagentId}、{@code subInvocationId} 与原始
     * {@code runEventType} 便于 UI 分组与日志关联。转发失败不得中断子 Agent 执行。</p>
     */
    private static final class ForwardingAgentEventSink implements AgentEventSink {
        private final InvocationContext forwardingContext;
        private final String subagentId;
        private final String subInvocationId;

        ForwardingAgentEventSink(
                InvocationContext forwardingContext,
                String subagentId, String subInvocationId) {
            this.forwardingContext = forwardingContext;
            this.subagentId = subagentId;
            this.subInvocationId = subInvocationId;
        }

        @Override
        public void emit(AgentRunEvent event) {
            try {
                if (event.type() == AgentRunEvent.Type.OUTPUT_DELTA
                        || event.type() == AgentRunEvent.Type.USAGE) {
                    // 高频事件不转发到领域事件流，避免淹没父的事件流；
                    // 子 Agent 的流式输出已在子 Agent 内部通过 SSE 直接推送。
                    return;
                }
                Map<String, Object> data = new HashMap<>(event.data());
                data.put("subagentId", subagentId);
                data.put("subInvocationId", subInvocationId);
                data.put("runEventType", event.type().name());
                forwardingContext.eventPublisher().publish(
                        DefaultAgentEvent.of(
                                forwardingContext,
                                mapToDomainType(event.type()),
                                event.message(),
                                data));
            } catch (RuntimeException ignored) {
                // 事件转发失败不得中断子 Agent 执行。
            }
        }

        /** AgentRunEvent.Type 到 AgentEventType 的语义映射。 */
        private static AgentEventType mapToDomainType(AgentRunEvent.Type type) {
            return switch (type) {
                case RUN_STARTED -> AgentEventType.AGENT_STARTED;
                case RUN_COMPLETED -> AgentEventType.AGENT_COMPLETED;
                case RUN_FAILED, RUN_CANCELLED -> AgentEventType.AGENT_FAILED;
                case TOOL_STARTED -> AgentEventType.TOOL_CALL_STARTED;
                case TOOL_FINISHED -> AgentEventType.TOOL_CALL_COMPLETED;
                case REPLAN, PLAN_CREATED, DECISION -> AgentEventType.STEP_STARTED;
                case ROUTE_DECIDED, ROUTE_REJECTED, ROUTE_CLARIFICATION_REQUIRED ->
                    AgentEventType.ROUTE_DECIDED;
                case OBSERVATION -> AgentEventType.STEP_COMPLETED;
                // OUTPUT_DELTA/USAGE 在 emit 中已提前过滤，这里给安全默认值满足穷举。
                case OUTPUT_DELTA, USAGE -> AgentEventType.MODEL_CALL_COMPLETED;
            };
        }
    }
}
