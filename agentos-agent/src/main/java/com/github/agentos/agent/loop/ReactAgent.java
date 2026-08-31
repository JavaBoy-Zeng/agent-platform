package com.github.agentos.agent.loop;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.ModelUsage;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.planner.flow.LlmToolDefinition;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 无显式计划、每轮即时决策的 Tool-Calling Agent（业界主流形态）。
 *
 * <p>与 {@link MainAgent} 的 Plan-and-Execute 不同，本 Agent 不生成前置计划：
 * 每一轮把当前对话（目标 + 全部工具观察）交给模型，由模型即时决定
 * “调用一个工具”或“直接给出最终回答”，工具结果回填后进入下一轮。</p>
 *
 * <p>工具调用走 <b>API 原生 function calling</b>：工具以 JSON Schema 随请求
 * 下发（{@link LlmRequest#tools()}），模型在协议层返回结构化 tool_use 块，
 * 正文与工具调用天然分离，不存在“解释文本 + JSON 混合输出”的解析问题。
 * 上下文体积由 {@link ConversationCompactor} 以规则方式控制；
 * 探索型大产出建议经 {@code AgentToolAdapter} 包装的子 Agent（如
 * {@code search-agent}）隔离执行，本循环只回收其最终结论。</p>
 *
 * <p>工具调用经 {@link ToolDispatcher} 统一调度，因此保留 HITL 审批与
 * 领域事件能力。高风险工具返回 pendingAction 时，运行转为 WAITING；
 * 与 MainAgent 不同，当前实现不持久化中间对话，审批通过后的恢复会
 * 从目标重新开始执行。</p>
 */
public final class ReactAgent implements Agent, AgentLoop {

    /** 注册到 AgentRegistry 的稳定标识。 */
    public static final String ID = "react-agent";

    /** assistant 工具调用消息的占位正文（协议允许空 content，本项目消息要求非空）。 */
    private static final String TOOL_CALL_PLACEHOLDER = "(工具调用)";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReactAgent.class);
    private static final int MAX_LOG_VALUE_LENGTH = 1_000;

    private final ChatClient chatClient;
    private final ToolDispatcher toolDispatcher;
    private final List<LlmToolDefinition> toolDefinitions;
    private final AgentExecutionLimits limits;
    private final ConversationCompactor compactor;
    private final ContinuationStore continuationStore;
    /** 内存中的续跑状态（快速路径）；进程重启后从持久化存储加载。 */
    private final java.util.concurrent.ConcurrentHashMap<String, ReactContinuation> continuations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final HistoryProcessor historyProcessor = new HistoryProcessor();
    /** 每多少次工具调用后插入一次反思（0 或负数表示关闭反思）。 */
    private final int reflectionInterval;
    /** 连续工具失败达到该阈值时强制触发反思。 */
    private final int consecutiveFailureThreshold;

    /**
     * 创建 React Agent。
     *
     * @param chatClient 支持原生 function calling 的模型客户端
     * @param toolDispatcher 统一工具调度边界（含 HITL 拦截器）
     * @param manifest 暴露给模型的工具清单；JSON Schema 定义从各工具派生，
     *                 执行经 dispatcher 注册表解析
     * @param limits 运行预算；每轮决策计一次模型调用
     * @param compactor 上下文压缩器
     * @param continuationStore 断点续跑状态持久化；为 null 时使用 NOOP（不持久化）
     * @param reflectionInterval 每多少次工具调用后插入反思；0 或负数表示关闭
     * @param consecutiveFailureThreshold 连续工具失败达到该值时强制触发反思；0 或负数表示关闭
     */
    public ReactAgent(
            ChatClient chatClient,
            ToolDispatcher toolDispatcher,
            List<AgentTool> manifest,
            AgentExecutionLimits limits,
            ConversationCompactor compactor,
            ContinuationStore continuationStore,
            int reflectionInterval,
            int consecutiveFailureThreshold) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.toolDispatcher = Objects.requireNonNull(
                toolDispatcher, "toolDispatcher must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        this.toolDefinitions = manifest.stream()
                .sorted(java.util.Comparator.comparing(AgentTool::name))
                .map(ReactAgent::toolDefinition)
                .collect(Collectors.toUnmodifiableList());
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
        this.continuationStore = continuationStore == null
                ? ContinuationStore.NOOP : continuationStore;
        this.reflectionInterval = reflectionInterval;
        this.consecutiveFailureThreshold = consecutiveFailureThreshold;
    }

    /** React Agent 的内存续跑状态：已累积的对话消息序列 + 挂起的工具调用。 */
    private record ReactContinuation(
            AgentRequest request,
            List<LlmMessage> messages,
            int modelCalls,
            int toolCalls,
            ToolCall pendingToolCall,
            String pendingToolCallId,
            String pendingToolName) {
    }

    /** 从工具清单派生原生 function calling 的 JSON Schema 工具定义。 */
    private static LlmToolDefinition toolDefinition(AgentTool tool) {
        return new LlmToolDefinition(
                tool.name(), tool.description(), tool.parametersSchema());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Plan-free native tool-calling agent that decides the next action each round";
    }

    @Override
    public AgentExecutionResult run(AgentRequest request, InvocationContext context) {
        AgentState state = run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
        return AgentExecutionResult.from(
                state, context.invocation() == null ? null : context.invocation().pendingAction());
    }

    @Override
    public AgentState run(AgentRequest request, InvocationContext context, AgentState runningState) {
        return run(request, context, runningState, AgentEventSink.NOOP);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        long started = System.nanoTime();
        String runId = "react-" + UUID.randomUUID();
        LOGGER.info("[react] started sessionId={} runId={}", request.sessionId(), runId);
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID, "loop", "react", "runId", runId)));

        // 会话历史（最近轮次）插到目标之前，与 SimpleQa/Main 路径保持一致的指代解析能力。
        List<LlmMessage> messages = new ArrayList<>(seedMessages(request));
        String systemInstruction = buildSystemInstruction();
        return runLoop(request, context, runningState, eventSink,
                messages, systemInstruction, 0, 0, 0, 0L, 0L, runId, started);
    }

    @Override
    public AgentState resume(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentCheckpoint checkpoint,
            PendingActionResolution resolution,
            AgentEventSink eventSink) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        String invocationId = checkpoint.invocationId();
        ReactContinuation continuation = takeContinuation(invocationId);
        if (continuation == null) {
            return runningState.fail(
                    "approved invocation cannot resume because its execution continuation is missing");
        }
        AgentRequest resumedRequest = continuation.request();
        if (!resumedRequest.sessionId().equals(request.sessionId())) {
            return runningState.fail("approved invocation continuation belongs to another session");
        }
        long started = System.nanoTime();
        String runId = "react-resume-" + UUID.randomUUID();
        LOGGER.info(
                "[react] resume started sessionId={} invocationId={} runId={} approved={}",
                request.sessionId(), invocationId, runId, resolution.approved());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                "审批恢复：" + (resolution.approved() ? "通过" : "拒绝"),
                Map.of(
                        "agentId", ID,
                        "loop", "react",
                        "runId", runId,
                        "resume", true,
                        "approved", resolution.approved())));

        // 在挂起点追加 TOOL 观察消息（与 assistant 工具调用按 callId 配对）。
        // 审批通过：执行工具，回填真实结果；拒绝：回填"用户拒绝"的失败观察。
        List<LlmMessage> messages = new ArrayList<>(continuation.messages());
        ToolCall pendingCall = continuation.pendingToolCall();
        String callId = continuation.pendingToolCallId() == null
                ? "call-" + continuation.toolCalls()
                : continuation.pendingToolCallId();
        String toolName = continuation.pendingToolName() == null
                ? (pendingCall == null ? "unknown" : pendingCall.toolName())
                : continuation.pendingToolName();
        String observation;
        int toolCalls = continuation.toolCalls();
        if (resolution.approved() && pendingCall != null) {
            int callNumber = toolCalls;
            ToolResult result = toolDispatcher.dispatch(
                    pendingCall,
                    tool -> new ToolContext(
                            resumedRequest,
                            context,
                            runId,
                            "step-" + callNumber,
                            limits,
                            Map.of(),
                            tool));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.TOOL_FINISHED,
                    request.sessionId(),
                    result.success() ? "工具执行完成" : "工具执行失败",
                    Map.of(
                            "agentId", ID,
                            "runId", runId,
                            "stepId", "step-" + toolCalls,
                            "toolName", toolName,
                            "status", result.success() ? "COMPLETED" : "FAILED",
                            "attempts", 1)));
            observation = result.success()
                    ? compactor.wrapToolResult(toolName, result.output())
                    : compactor.wrapToolResult(
                            toolName,
                            "工具执行失败 [" + result.failureType().name() + "]: "
                                    + result.error());
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.OBSERVATION,
                    request.sessionId(),
                    toolName + (result.success()
                            ? " 执行成功，返回 " + result.output().length() + " 个字符"
                            : " 执行失败：" + result.error()),
                    Map.of(
                            "agentId", ID,
                            "runId", runId,
                            "stepId", "step-" + toolCalls,
                            "toolName", toolName,
                            "status", result.success() ? "COMPLETED" : "FAILED",
                            "failureType", result.failureType().name(),
                            "attempts", 1)));
            if (context.invocation() != null) {
                context.invocation().incrementToolCalls();
            }
        } else {
            observation = compactor.wrapToolResult(
                    toolName,
                    "用户" + (resolution.approved() ? "审批通过但工具调用参数缺失"
                            : "拒绝了该工具调用"));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.OBSERVATION,
                    request.sessionId(),
                    toolName + " 审批" + (resolution.approved() ? "通过" : "拒绝"),
                    Map.of(
                            "agentId", ID,
                            "runId", runId,
                            "stepId", "step-" + toolCalls,
                            "toolName", toolName,
                            "status", "REJECTED",
                            "attempts", 1)));
        }
        messages.add(LlmMessage.toolResult(callId, observation));
        if (context.invocation() != null) {
            context.invocation().clearPendingAction();
        }
        String systemInstruction = buildSystemInstruction();
        return runLoop(resumedRequest, context, runningState, eventSink,
                messages, systemInstruction,
                continuation.modelCalls(), toolCalls, 0, 0L, 0L, runId, started);
    }

    @Override
    public AgentCheckpoint checkpoint(
            AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
        // ReactAgent 的 checkpoint 不扩展外部快照——续跑状态由 ContinuationStore 单独管理。
        return checkpoint;
    }

    @Override
    public void discard(AgentCheckpoint checkpoint) {
        continuations.remove(checkpoint.invocationId());
        deleteContinuationQuietly(checkpoint.invocationId());
    }

    /**
     * 核心循环：每轮即时决策，工具调用经原生协议回填后进入下一轮。
     *
     * <p>抽取自 {@link #run} 与 {@link #resume} 共用——resume 时在挂起点
     * 追加 TOOL 观察后从这里继续，modelCalls/toolCalls 从断点值恢复。</p>
     */
    private AgentState runLoop(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink,
            List<LlmMessage> messages,
            String systemInstruction,
            int modelCalls,
            int toolCalls,
            int consecutiveFailures,
            long totalInputTokens,
            long totalOutputTokens,
            String runId,
            long started) {
        try {
            while (true) {
                context.throwIfCancelled();
                if (modelCalls >= limits.maxModelCalls()) {
                    return failed(request, runningState, eventSink, started, modelCalls, toolCalls,
                            totalInputTokens, totalOutputTokens,
                            "maxModelCalls exhausted: " + limits.maxModelCalls()
                                    + "（每轮决策计一次模型调用，react 模式可通过"
                                    + " agentos.agent.react.max-model-calls 提高预算）");
                }
                modelCalls++;
                RoundDecision decision = decide(
                        request, eventSink, systemInstruction, messages, modelCalls);
                emitUsage(request, eventSink, decision.usage());
                if (decision.usage() != null) {
                    totalInputTokens += decision.usage().promptTokens();
                    totalOutputTokens += decision.usage().completionTokens();
                }
                if (decision.toolCall() == null) {
                    String answer = decision.answer();
                    if (!decision.streamedLive()) {
                        // 模型未走流式或最终回答无增量时补一次伪流式输出。
                        eventSink.emit(AgentRunEvent.of(
                                AgentRunEvent.Type.OUTPUT_DELTA,
                                request.sessionId(),
                                answer,
                                Map.of(
                                        "agentId", ID,
                                        "sequence", 0,
                                        "source", "model-sse")));
                    }
                    LOGGER.info(
                            "[react] finished sessionId={} runId={} status=COMPLETED modelCalls={} toolCalls={} answerChars={} durationMs={}",
                            request.sessionId(), runId, modelCalls, toolCalls, answer.length(),
                            elapsedMillis(started));
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.RUN_COMPLETED,
                            request.sessionId(),
                            answer,
                            Map.of(
                                    "agentId", ID,
                                    "loop", "react",
                                    "runId", runId,
                                    "modelCalls", modelCalls,
                                    "toolCalls", toolCalls,
                                    "totalInputTokens", totalInputTokens,
                                    "totalOutputTokens", totalOutputTokens,
                                    "totalTokens", totalInputTokens + totalOutputTokens)));
                    return runningState.complete(answer, decision.reasoningContent());
                }

                if (toolCalls >= limits.maxToolCalls()) {
                    return failed(request, runningState, eventSink, started, modelCalls, toolCalls,
                            totalInputTokens, totalOutputTokens,
                            "maxToolCalls exhausted: " + limits.maxToolCalls());
                }
                toolCalls++;
                ToolCall call = decision.toolCall();
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.DECISION,
                        request.sessionId(),
                        "选择工具 " + call.toolName(),
                        Map.of(
                                "agentId", ID,
                                "runId", runId,
                                "outcome", "CONTINUE",
                                "reason", "TOOL_SELECTED",
                                "toolName", call.toolName(),
                                "modelCalls", modelCalls,
                                "toolCalls", toolCalls)));
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.TOOL_STARTED,
                        request.sessionId(),
                        "调用工具 " + call.toolName(),
                        Map.of(
                                "agentId", ID,
                                "runId", runId,
                                "stepId", "step-" + toolCalls,
                                "toolName", call.toolName(),
                                "position", toolCalls,
                                "stepCount", limits.maxToolCalls())));

                int callNumber = toolCalls;
                ToolResult result = toolDispatcher.dispatch(
                        call,
                        tool -> new ToolContext(
                                request,
                                context,
                                runId,
                                "step-" + callNumber,
                                limits,
                                Map.of(),
                                tool));

                PendingAction pendingAction = result.actions().pendingAction();
                if (pendingAction != null) {
                    if (context.invocation() != null) {
                        context.invocation().waitFor(pendingAction);
                    }
                    // 保存断点状态到内存与持久化存储——审批恢复时从该点继续。
                    String callId = decision.toolCallId() == null
                            ? "call-" + callNumber : decision.toolCallId();
                    messages.add(LlmMessage.assistantToolCallWithReasoning(
                            decision.answer().isBlank() ? TOOL_CALL_PLACEHOLDER : decision.answer(),
                            decision.reasoningContent(),
                            new LlmMessage.ToolCallPart(
                                    callId, call.toolName(), argumentsJson(call))));
                    saveContinuation(context.invocationId(), new ReactContinuation(
                            request, List.copyOf(messages), modelCalls, toolCalls,
                            call, callId, call.toolName()));
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.DECISION,
                            request.sessionId(),
                            pendingAction.description(),
                            Map.of(
                                    "agentId", ID,
                                    "runId", runId,
                                    "outcome", "WAITING",
                                    "pendingActionId", pendingAction.pendingActionId(),
                                    "pendingActionType", pendingAction.type().name(),
                                    "title", pendingAction.title(),
                                    "continuationSaved", true)));
                    try {
                        context.eventPublisher().publish(DefaultAgentEvent.of(
                                context,
                                AgentEventType.HUMAN_ACTION_REQUIRED,
                                pendingAction.description(),
                                Map.of(
                                        "pendingActionId", pendingAction.pendingActionId(),
                                        "type", pendingAction.type().name(),
                                        "title", pendingAction.title())));
                    } catch (RuntimeException ignored) {
                        // 观察端不得中断挂起流程。
                    }
                    return runningState.waitForAction(pendingAction.description());
                }

                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.TOOL_FINISHED,
                        request.sessionId(),
                        result.success() ? "工具执行完成" : "工具执行失败",
                        Map.of(
                                "agentId", ID,
                                "runId", runId,
                                "stepId", "step-" + toolCalls,
                                "toolName", call.toolName(),
                                "status", result.success() ? "COMPLETED" : "FAILED",
                                "attempts", 1)));
                String observation = result.success()
                        ? compactor.wrapToolResult(call.toolName(), result.output())
                        : compactor.wrapToolResult(
                                call.toolName(),
                                "工具执行失败 [" + result.failureType().name() + "]: "
                                        + result.error());
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.OBSERVATION,
                        request.sessionId(),
                        call.toolName() + (result.success()
                                ? " 执行成功，返回 " + result.output().length() + " 个字符"
                                : " 执行失败：" + result.error()),
                        Map.of(
                                "agentId", ID,
                                "runId", runId,
                                "stepId", "step-" + toolCalls,
                                "toolName", call.toolName(),
                                "status", result.success() ? "COMPLETED" : "FAILED",
                                "failureType", result.failureType().name(),
                                "attempts", 1)));
                if (context.invocation() != null) {
                    context.invocation().incrementToolCalls();
                }
                // 原生协议回填：assistant 携带 tool_use，TOOL 消息按 id 配对回传结果。
                String callId = decision.toolCallId() == null
                        ? "call-" + callNumber : decision.toolCallId();
                messages.add(LlmMessage.assistantToolCallWithReasoning(
                        decision.answer().isBlank() ? TOOL_CALL_PLACEHOLDER : decision.answer(),
                        decision.reasoningContent(),
                        new LlmMessage.ToolCallPart(
                                callId, call.toolName(), argumentsJson(call))));
                messages.add(LlmMessage.toolResult(callId, observation));

                // 反思触发：周期性或连续失败达阈值时，让模型显式评估进度，避免死循环。
                consecutiveFailures = result.success() ? 0 : consecutiveFailures + 1;
                ReflectionOutcome reflection = maybeReflect(
                        request, context, eventSink, systemInstruction, messages,
                        toolCalls, consecutiveFailures, runId);
                if (reflection != null) {
                    messages.add(reflection.message());
                    if (reflection.abort()) {
                        // 反思判定任务无法继续，直接失败。
                        return failed(request, runningState, eventSink, started,
                                modelCalls, toolCalls,
                                totalInputTokens, totalOutputTokens,
                                reflection.reason());
                    }
                    modelCalls = reflection.modelCalls();
                }

                ConversationCompactor.Compaction compaction = compactor.compact(messages);
                if (compaction.changed()) {
                    messages = new ArrayList<>(compaction.messages());
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.DECISION,
                            request.sessionId(),
                            "上下文超限，压缩 " + compaction.compressedCount() + " 条、丢弃 "
                                    + compaction.droppedCount() + " 条旧工具观察",
                            Map.of(
                                    "agentId", ID,
                                    "runId", runId,
                                    "outcome", "CONTINUE",
                                    "reason", "CONTEXT_COMPACTED",
                                    "compressedCount", compaction.compressedCount(),
                                    "droppedCount", compaction.droppedCount())));
                    LOGGER.info(
                            "[react] compacted sessionId={} runId={} compressed={} dropped={} totalChars={}",
                            request.sessionId(), runId, compaction.compressedCount(),
                            compaction.droppedCount(),
                            ConversationCompactor.totalChars(messages));
                }
            }
        } catch (RuntimeException exception) {
            if (context.isCancelled()
                    || Thread.currentThread().isInterrupted()
                    || exception instanceof java.util.concurrent.CancellationException) {
                LOGGER.info("[react] cancelled sessionId={} durationMs={}",
                        request.sessionId(), elapsedMillis(started));
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.RUN_CANCELLED,
                        request.sessionId(),
                        "run cancelled by user",
                        Map.of(
                                "agentId", ID, "loop", "react",
                                "totalInputTokens", totalInputTokens,
                                "totalOutputTokens", totalOutputTokens,
                                "totalTokens", totalInputTokens + totalOutputTokens)));
                return runningState.cancel("run cancelled by user");
            }
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[react] failed sessionId={} error={}",
                    request.sessionId(), logValue(message), exception);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of(
                            "agentId", ID, "loop", "react",
                            "totalInputTokens", totalInputTokens,
                            "totalOutputTokens", totalOutputTokens,
                            "totalTokens", totalInputTokens + totalOutputTokens)));
            return runningState.fail(message);
        }
    }

    /**
     * 取出续跑状态：优先内存，其次持久化存储（同时删除，语义与内存 remove 对齐）。
     */
    private ReactContinuation takeContinuation(String invocationId) {
        ReactContinuation inMemory = continuations.remove(invocationId);
        if (inMemory != null) {
            deleteContinuationQuietly(invocationId);
            return inMemory;
        }
        return continuationStore.load(invocationId)
                .map(loaded -> new ReactContinuation(
                        loaded.request(),
                        loaded.reactMessages(),
                        loaded.modelCalls(),
                        loaded.toolCalls(),
                        loaded.pendingToolCall(),
                        loaded.pendingToolCallId(),
                        loaded.pendingToolName()))
                .orElse(null);
    }

    /** 反思触发的结果：追加到 messages 的反思消息 + 是否中止任务。 */
    private record ReflectionOutcome(
            LlmMessage message, boolean abort, String reason, int modelCalls) {
    }

    /**
     * 周期性或连续失败达阈值时让模型显式评估进度。
     *
     * <p>触发条件（满足任一）：</p>
     * <ul>
     *   <li>{@code reflectionInterval > 0} 且 {@code toolCalls} 是其整数倍</li>
     *   <li>{@code consecutiveFailureThreshold > 0} 且连续失败达到该阈值</li>
     * </ul>
     *
     * <p>反思走一次轻量模型调用（不带 tools），让模型输出 JSON：
     * {@code {"continue": true/false, "reason": "..."}}。
     * {@code continue=false} 时中止任务并以 reason 作为失败原因。</p>
     *
     * @return 反思结果；未触发时返回 null
     */
    private ReflectionOutcome maybeReflect(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String systemInstruction,
            List<LlmMessage> messages,
            int toolCalls,
            int consecutiveFailures,
            String runId) {
        boolean periodic = reflectionInterval > 0 && toolCalls > 0
                && toolCalls % reflectionInterval == 0;
        boolean failureDriven = consecutiveFailureThreshold > 0
                && consecutiveFailures >= consecutiveFailureThreshold;
        if (!periodic && !failureDriven) {
            return null;
        }
        String trigger = failureDriven ? "CONSECUTIVE_FAILURE" : "PERIODIC";
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.DECISION,
                request.sessionId(),
                "触发反思（" + trigger + "）：让模型评估进度",
                Map.of(
                        "agentId", ID,
                        "runId", runId,
                        "outcome", "REFLECT",
                        "reason", trigger,
                        "toolCalls", toolCalls,
                        "consecutiveFailures", consecutiveFailures)));
        try {
            String reflectionPrompt = """
                    你刚刚执行了若干工具调用。请评估当前进度并决定是否继续。
                    只输出 JSON，不要其他文本：
                    {"continue": true/false, "reason": "简短说明"}
                    continue=false 表示任务无法继续（如方向错误、工具反复失败、目标已达成无需更多调用）。
                    """;
            List<LlmMessage> reflectionMessages = new ArrayList<>();
            reflectionMessages.add(LlmMessage.user("已执行工具调用次数：" + toolCalls
                    + "，连续失败：" + consecutiveFailures
                    + "。请评估并输出 JSON。"));
            // 反思走一次模型调用（无 tools），让模型自检进度。
            LlmRequest reflectionRequest = new LlmRequest(
                    systemInstruction + "\n\n[反思阶段] " + reflectionPrompt,
                    reflectionMessages).withTools(List.of()).withRouting(request);
            ChatClient.ToolCallResponse response = chatClient.chatWithTools(
                    request.sessionId(), reflectionRequest, delta -> { });
            String answer = response.answer() == null ? "" : response.answer().strip();
            int newModelCalls = context.invocation() != null
                    ? context.invocation().incrementModelCalls() : 0;
            boolean shouldContinue = !answer.toLowerCase().contains("\"continue\":false");
            String reason = extractJsonField(answer, "reason");
            if (reason.isBlank()) {
                reason = shouldContinue ? "反思通过，继续执行" : "反思判定中止";
            }
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.OBSERVATION,
                    request.sessionId(),
                    "反思结论：" + (shouldContinue ? "继续" : "中止") + " — " + reason,
                    Map.of(
                            "agentId", ID,
                            "runId", runId,
                            "reflection", true,
                            "shouldContinue", shouldContinue,
                            "trigger", trigger,
                            "reason", reason)));
            LlmMessage reflectionMsg = LlmMessage.assistant(
                    "[反思] " + (shouldContinue ? "继续" : "中止") + "：" + reason);
            return new ReflectionOutcome(reflectionMsg, !shouldContinue, reason, newModelCalls);
        } catch (RuntimeException exception) {
            LOGGER.warn("[react] reflection failed sessionId={} runId={}: {}",
                    request.sessionId(), runId, exception.getMessage());
            // 反思失败不中断主循环，返回 null 跳过。
            return null;
        }
    }

    /** 从 JSON 文本中提取指定字段的字符串值；找不到时返回空字符串。 */
    private static String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return "";
        }
        char ch = json.charAt(start);
        if (ch == '"') {
            int end = json.indexOf('"', start + 1);
            return end > 0 ? json.substring(start + 1, end) : "";
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && !Character.isWhitespace(json.charAt(end))) {
            end++;
        }
        return json.substring(start, end);
    }

    /** 写透持久化；失败只记日志，不中断已进入 WAITING 的运行。 */
    private void saveContinuation(String invocationId, ReactContinuation continuation) {
        continuations.put(invocationId, continuation);
        try {
            continuationStore.save(invocationId,
                    ContinuationStore.PersistedContinuation.forReact(
                            continuation.request(),
                            continuation.messages(),
                            continuation.modelCalls(),
                            continuation.toolCalls(),
                            continuation.pendingToolCall(),
                            continuation.pendingToolCallId(),
                            continuation.pendingToolName()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[react-continuation] persist failed invocationId={} error={}",
                    invocationId, exception.getMessage());
        }
    }

    private void deleteContinuationQuietly(String invocationId) {
        try {
            continuationStore.delete(invocationId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[react-continuation] delete failed invocationId={} error={}",
                    invocationId, exception.getMessage());
        }
    }

    private void emitUsage(
            AgentRequest request, AgentEventSink eventSink, ModelUsage usage) {
        if (usage == null) {
            return;
        }
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.USAGE,
                request.sessionId(),
                "模型用量",
                Map.of(
                        "agentId", ID,
                        "model", usage.model(),
                        "promptTokens", usage.promptTokens(),
                        "completionTokens", usage.completionTokens(),
                        "totalTokens", usage.totalTokens())));
    }

    private List<LlmMessage> seedMessages(AgentRequest request) {
        LlmRequest seeded = historyProcessor.process(
                new LlmRequest(null, List.of(LlmMessage.user("目标：" + request.objective()))),
                request);
        return seeded.messages();
    }

    /**
     * 执行一轮原生 function calling 决策：结构化工具调用或最终回答。
     *
     * <p>正文增量直接透传为 OUTPUT_DELTA——协议层正文与工具调用分离，
     * 无需任何前缀探测或 JSON 文本解析。</p>
     */
    private RoundDecision decide(
            AgentRequest request,
            AgentEventSink eventSink,
            String systemInstruction,
            List<LlmMessage> messages,
            int modelCalls) {
        LOGGER.info("[react] decision started sessionId={} modelCall={}/{} messageCount={}",
                request.sessionId(), modelCalls, limits.maxModelCalls(), messages.size());
        AtomicInteger sequence = new AtomicInteger();
        AtomicBoolean streamed = new AtomicBoolean(false);
        ChatClient.ToolCallResponse response = chatClient.chatWithTools(
                request.sessionId(),
                new LlmRequest(systemInstruction, List.copyOf(messages), "", toolDefinitions)
                        .withRouting(request),
                delta -> {
                    streamed.set(true);
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.OUTPUT_DELTA,
                            request.sessionId(),
                            delta,
                            Map.of(
                                    "agentId", ID,
                                    "sequence", sequence.getAndIncrement(),
                                    "source", "model-sse")));
                });
        String answer = response.answer() == null ? "" : response.answer().strip();
        LOGGER.info("[react] decision finished sessionId={} toolCall={} answer={}",
                request.sessionId(),
                response.toolCall() == null ? "-" : response.toolCall().toolName(),
                logValue(answer));
        return new RoundDecision(
                answer, response.reasoningContent(), response.usage(),
                response.toolCall(), response.toolCallId(), streamed.get());
    }

    private static String argumentsJson(ToolCall call) {
        if (call.arguments() == null || call.arguments().isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : call.arguments().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(
                        String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
        }
        return json.append('}').toString();
    }

    private String buildSystemInstruction() {
        return """
                你是 AgentOS 的 React Agent，通过“观察-行动”循环完成用户目标。
                没有预先生成的执行计划：每一轮基于目标与已有观察即时决定下一步。

                工具经协议层原生提供（见本次请求的 tools 定义），通过原生工具调用
                机制发起调用；需要回答时直接输出正文文本。

                规则：
                - 每轮至多调用一个工具；工具结果会以工具消息追加。
                - 旧工具结果可能被压缩为 “[compressed]” 前缀的摘要；需要完整细节时重新调用工具。
                - 工具失败会以失败观察返回，修正参数后重试，不要编造工具结果。
                - 长文档、多来源检索等大范围探索优先委托子 Agent 工具（如 search-agent），
                  它们在独立上下文中执行，只返回结论。
                - 目标完成后立即给出最终回答，不要继续调用工具。
                """;
    }

    private AgentState failed(
            AgentRequest request,
            AgentState runningState,
            AgentEventSink eventSink,
            long started,
            int modelCalls,
            int toolCalls,
            long totalInputTokens,
            long totalOutputTokens,
            String error) {
        LOGGER.warn(
                "[react] finished sessionId={} status=FAILED modelCalls={} toolCalls={} error={} durationMs={}",
                request.sessionId(), modelCalls, toolCalls, logValue(error),
                elapsedMillis(started));
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_FAILED,
                request.sessionId(),
                error,
                Map.of(
                        "agentId", ID,
                        "loop", "react",
                        "modelCalls", modelCalls,
                        "toolCalls", toolCalls,
                        "totalInputTokens", totalInputTokens,
                        "totalOutputTokens", totalOutputTokens,
                        "totalTokens", totalInputTokens + totalOutputTokens)));
        return runningState.fail(error);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String logValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= MAX_LOG_VALUE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
    }

    /** 一轮决策的产物。 */
    private record RoundDecision(
            String answer,
            String reasoningContent,
            ModelUsage usage,
            ToolCall toolCall,
            String toolCallId,
            boolean streamedLive) {
    }
}
