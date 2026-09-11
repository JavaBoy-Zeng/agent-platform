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
 * invocationId 与本地计数器），模型调用共享根任务预算，用量归根会话；
 * 子 Agent 的多轮 LLM 对话作为局部变量天然不进入父的 messages；
 * 取消令牌级联父的 cancellation（父取消时子自动取消）；子 Agent 的运行事件
 * 经 {@link ForwardingAgentEventSink} 转发到父的事件流，UI 可观测子 Agent
 * 的执行过程。</p>
 *
 * <p>子 Agent 挂起时保存独立检查点，把实际操作审批传给父循环；恢复时按
 * 子 invocationId 恢复原流程，拒绝时递归清理。</p>
 */
public final class AgentToolAdapter implements com.github.agentos.tool.api.AgentDelegationTool {

    /** 子 Agent 任务目标参数名。 */
    public static final String OBJECTIVE_PARAMETER = "objective";

    /** 可选的会话归属参数名。 */
    public static final String SESSION_ID_PARAMETER = "sessionId";

    private final BaseAgent agent;
    private final RiskLevel riskLevel;
    private final com.github.agentos.kernel.CheckpointStore checkpoints;
    private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

    /** 创建以低风险工具形态暴露指定 Agent 的适配器。 */
    public AgentToolAdapter(BaseAgent agent) {
        this(agent, RiskLevel.LOW);
    }

    /** 创建以指定风险等级暴露指定 Agent 的适配器。 */
    public AgentToolAdapter(BaseAgent agent, RiskLevel riskLevel) {
        this(agent, riskLevel, new com.github.agentos.kernel.InMemoryCheckpointStore());
    }

    public AgentToolAdapter(BaseAgent agent, RiskLevel riskLevel,
            com.github.agentos.kernel.CheckpointStore checkpoints) {
        this.checkpoints = Objects.requireNonNull(checkpoints);
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
        InvocationContext parent = toolContext.invocation();
        var parentInvocation = parent.invocation();
        var pending = parentInvocation == null ? null : parentInvocation.pendingAction();
        com.github.agentos.kernel.AgentCheckpoint saved = null;
        if (pending != null && name().equals(pending.payload().get("delegationTool"))) {
            if (!Objects.equals(pending.payload().get("delegationArguments"), call.arguments())) {
                return ToolResult.failure(ToolFailureType.PERMISSION_DENIED, "delegation arguments changed");
            }
            saved = checkpoints.load(String.valueOf(pending.payload().get("delegationInvocation")))
                    .orElseThrow(() -> new IllegalStateException("child checkpoint is missing"));
            if (!saved.teamId().equals(parent.teamId()) || !saved.userId().equals(parent.userId())
                    || !saved.invocationId().startsWith(parent.invocationId() + "/sub/")) {
                return ToolResult.failure(ToolFailureType.PERMISSION_DENIED, "child checkpoint owner mismatch");
            }
        }
        String sessionId = saved != null ? saved.sessionId()
                : call.arguments().get(SESSION_ID_PARAMETER) instanceof String value && !value.isBlank()
                        ? value : "agent-tool-" + UUID.randomUUID();
        String childId = saved != null ? saved.invocationId()
                : parent.invocationId() + "/sub/" + agent.id() + "/" + UUID.randomUUID();
        AgentInvocation child = new AgentInvocation(childId, sessionId, agent.id(), parent.taskId(), Instant.now());
        if (parentInvocation != null) child.shareModelCallBudget(parentInvocation);
        child.start();
        InvocationContext childContext = parent.withAgentId(agent.id()).withInvocation(child);
        AgentEventSink sink = new ForwardingAgentEventSink(parent.withAgentId(agent.id()), agent.id(), childId);
        Map<String, Object> attributes = new HashMap<>(toolContext.request().attributes());
        // 委派目标必须自包含，不能重复注入父会话的整段历史。
        attributes.remove("conversationHistory");
        AgentRequest request = saved == null
                ? new AgentRequest(sessionId, text, attributes)
                : mapper.readValue(saved.state().get("delegation.request"), AgentRequest.class);
        AgentState state;
        if (saved == null) {
            state = agent.run(request, childContext, AgentState.ready().startNextIteration(), sink);
        } else {
            var resolution = parentInvocation.resolution();
            if (resolution == null || !resolution.approved()
                    || !pending.pendingActionId().equals(resolution.pendingActionId())) {
                return ToolResult.failure(ToolFailureType.PERMISSION_DENIED, "child approval is missing");
            }
            child.waitFor(saved.pendingAction());
            child.resolve(resolution);
            for (int n = 0; n < saved.executionCounters().modelCalls(); n++) child.incrementModelCalls();
            for (int n = 0; n < saved.executionCounters().toolCalls(); n++) child.incrementToolCalls();
            for (int n = 0; n < saved.executionCounters().steps(); n++) child.incrementSteps();
            for (int n = 0; n < saved.executionCounters().replans(); n++) child.incrementReplans();
            // 批准只移交给原子任务，不扩展到它的其他操作。
            parentInvocation.clearPendingAction();
            parentInvocation.resolve(null);
            state = agent.resume(request, childContext, AgentState.ready().startNextIteration(),
                    saved, resolution, sink);
        }
        child.finish(state);
        if (state.status() == AgentState.Status.WAITING) {
            var action = child.pendingAction();
            if (action == null) return ToolResult.failure(ToolFailureType.PERMISSION_DENIED,
                    "waiting child has no resumable pending action");
            var base = new com.github.agentos.kernel.AgentCheckpoint(sessionId, childId, agent.id(),
                    parent.taskId(), parent.teamId(), parent.userId(), text, "", "", 0, List.of(),
                    Map.of("delegation.request", mapper.writeValueAsString(request)), action,
                    new com.github.agentos.kernel.ExecutionCounters(child.modelCalls(), child.toolCalls(),
                            child.replans(), child.steps()), com.github.agentos.kernel.AgentRunStatus.WAITING,
                    Instant.now());
            checkpoints.save(agent.checkpoint(request, childContext, base));
            Map<String, Object> payload = new HashMap<>(action.payload());
            payload.put("delegationTool", name());
            payload.put("delegationInvocation", childId);
            payload.put("delegationArguments", call.arguments());
            return ToolResult.pending(new com.github.agentos.kernel.PendingAction(action.pendingActionId(),
                    action.type(), action.title(), action.description(), payload));
        }
        checkpoints.delete(childId);
        return state.status() == AgentState.Status.COMPLETED ? ToolResult.success(state.output())
                : ToolResult.failure(state.status() == AgentState.Status.CANCELLED
                        ? ToolFailureType.CANCELLED : ToolFailureType.TOOL_INTERNAL_ERROR,
                        (state.error().isBlank() ? state.status().name() : state.error())
                                + (state.output().isBlank() ? "" : "\n[partial observations]\n" + state.output()));
    }

    @Override public void discardPending(com.github.agentos.kernel.PendingAction action) {
        if (action == null || !name().equals(action.payload().get("delegationTool"))) return;
        String childId = String.valueOf(action.payload().get("delegationInvocation"));
        checkpoints.load(childId).ifPresent(agent::discard);
        checkpoints.delete(childId);
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
                data.put("stepId", subInvocationId + "/" + event.data().getOrDefault("stepId", "progress"));
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
