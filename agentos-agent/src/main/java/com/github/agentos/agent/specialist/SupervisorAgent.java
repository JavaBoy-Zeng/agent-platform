package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.RouteAcceptance;
import com.github.agentos.agent.routing.RoutableAgent;
import com.github.agentos.agent.routing.SupervisorRouteDecision;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/** 使用结构化作用域和能力决策派发专业 Agent，并统一受限模式下的工具审批边界。 */
public final class SupervisorAgent implements Agent, AgentLoop {
    public static final String ID = "supervisor-agent";
    public static final double DEFAULT_MIN_CONFIDENCE = 0.75;

    private static final String CLASSIFY_INSTRUCTION = """
            你是 AgentOS 任务路由器。结合会话历史和当前目标输出一个 JSON 对象，禁止输出解释或 Markdown。

            scope 只能是：LOCAL_RUNTIME、LOCAL_WORKSPACE、EXTERNAL_WORLD、GENERAL。
            requiredCapabilities 只能包含：RUNTIME_CATALOG_READ、WEB_RESEARCH、CODE_WRITE、
            COMMAND_EXECUTION、DOCUMENT_GENERATION、GENERAL_PLANNING。
            targetAgent 只能是：search-agent、code-agent、report-agent、main-agent。

            规则：
            - 当前 AgentOS、当前系统、已注册工具/Agent/Skill/MCP/模型属于 LOCAL_RUNTIME，交 main-agent；
              上游通常会确定性拦截，此处不得选择 search-agent。
            - search-agent 仅限时效性检索：答案随时间变化、必须联网才能获得的外部事实
              （最新版本、新闻、今日天气、当前价格、实时行情等），并要求 WEB_RESEARCH。
            - 知识整理类任务（梳理、罗列、总结、介绍、科普、对比、解释某领域的既有知识）
              一律交 main-agent，scope 记为 GENERAL：这类问题应以模型自身知识为主体作答，
              检索最多是补充，绝不能只复述搜索摘要，因此不得选择 search-agent。
            - 本地代码编写或执行选择 code-agent；生成落盘文档选择 report-agent。
            - 多步骤、混合能力或无法可靠判断时选择 main-agent。
            - 作用域歧义且会改变工具选择时，将 confidence 设为低于 0.75，并提供 clarifyingQuestion。

            JSON 字段：intent、scope、requiredCapabilities、targetAgent、confidence、reason、clarifyingQuestion。
            clarifyingQuestion 不需要时返回空字符串。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(SupervisorAgent.class);

    private final ChatClient chatClient;
    private final Map<String, Agent> specialists;
    private final AgentLoop fallback;
    /** fallback 的展示标识：fallback 实现 {@link Agent} 时取其 id，否则按 main-agent 记账。 */
    private final String fallbackId;
    private final ObjectMapper objectMapper;
    private final double minConfidence;
    private final HistoryProcessor historyProcessor = new HistoryProcessor();

    public SupervisorAgent(
            ChatClient chatClient, Map<String, Agent> specialists, AgentLoop fallback) {
        this(chatClient, specialists, fallback, new ObjectMapper(), DEFAULT_MIN_CONFIDENCE);
    }

    public SupervisorAgent(
            ChatClient chatClient,
            Map<String, Agent> specialists,
            AgentLoop fallback,
            ObjectMapper objectMapper,
            double minConfidence) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.specialists = Map.copyOf(Objects.requireNonNull(
                specialists, "specialists must not be null"));
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
        this.fallbackId = fallback instanceof Agent agent ? agent.id() : "main-agent";
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (!Double.isFinite(minConfidence) || minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException("minConfidence must be within [0.0, 1.0]");
        }
        this.minConfidence = minConfidence;
    }

    @Override public String id() {
        return ID;
    }

    @Override public String description() {
        return "Structured scope-and-capability router for specialist agents";
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
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED, request.sessionId(), request.objective(),
                Map.of("agentId", ID)));

        SupervisorRouteDecision decision;
        try {
            LlmRequest classifyRequest = historyProcessor.process(
                    new LlmRequest(CLASSIFY_INSTRUCTION,
                            java.util.List.of(LlmMessage.user(request.objective())))
                            .withRouting(request),
                    request);
            decision = parseDecision(chatClient.chat(request.sessionId(), classifyRequest));
        } catch (RuntimeException exception) {
            return rejectAndFallback(request, context, runningState, eventSink,
                    "INVALID_ROUTE_DECISION", safeMessage(exception), null);
        }

        emitRouteDecision(request, eventSink, decision);
        if (decision.confidence() < minConfidence) {
            if (decision.requiresClarification()) {
                return clarify(request, runningState, eventSink, decision);
            }
            return rejectAndFallback(request, context, runningState, eventSink,
                    "LOW_CONFIDENCE", "route confidence below " + minConfidence, decision);
        }
        if ("main-agent".equals(decision.targetAgent())) {
            return fallback(request, context, runningState, eventSink);
        }

        Agent specialist = specialists.get(decision.targetAgent());
        if (specialist == null) {
            return rejectAndFallback(request, context, runningState, eventSink,
                    "UNKNOWN_AGENT", "unregistered target agent", decision);
        }
        if (!(specialist instanceof AgentLoop specialistLoop)
                || !(specialist instanceof RoutableAgent routable)) {
            return rejectAndFallback(request, context, runningState, eventSink,
                    "AGENT_NOT_ROUTABLE", "target does not expose a routing contract", decision);
        }
        RouteAcceptance acceptance = routable.accepts(request, decision);
        if (!acceptance.accepted()) {
            return rejectAndFallback(request, context, runningState, eventSink,
                    "AGENT_REJECTED", acceptance.reason(), decision);
        }
        if (requiresCentralToolDispatch(request)) {
            // 专业 Agent 内部仍有少量直连工具调用，直接派发会绕开 ToolDispatcher 的
            // HITL 拦截器。非 FULL_ACCESS 模式统一回到 MainAgent，让专业 Agent 作为
            // 带风险等级的工具执行，从而在任何文件写入、命令或联网动作前先挂起审批。
            return rejectAndFallback(request, context, runningState, eventSink,
                    "CENTRAL_APPROVAL_REQUIRED",
                    "当前权限模式要求通过统一工具审批链执行", decision);
        }

        LOGGER.info("[supervisor] dispatching sessionId={} target={} scope={} confidence={}",
                request.sessionId(), decision.targetAgent(), decision.scope(), decision.confidence());
        return specialistLoop.run(request, context.withAgentId(decision.targetAgent()),
                runningState, eventSink);
    }

    private static boolean requiresCentralToolDispatch(AgentRequest request) {
        Object configured = request.attributes().getOrDefault("approvalMode", "RISK_BASED");
        String mode = String.valueOf(configured).trim()
                .toUpperCase(java.util.Locale.ROOT);
        return !"FULL_ACCESS".equals(mode);
    }

    private SupervisorRouteDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty route decision");
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstNewline < 0 || closingFence <= firstNewline) {
                throw new IllegalArgumentException("invalid fenced route decision");
            }
            json = json.substring(firstNewline + 1, closingFence).trim();
        }
        return objectMapper.readValue(json, SupervisorRouteDecision.class);
    }

    private static void emitRouteDecision(
            AgentRequest request, AgentEventSink eventSink, SupervisorRouteDecision decision) {
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.ROUTE_DECIDED,
                request.sessionId(), decision.reason(),
                Map.of(
                        "intent", decision.intent(),
                        "scope", decision.scope().name(),
                        "requiredCapabilities", decision.requiredCapabilities().stream()
                                .map(Enum::name).sorted().toList(),
                        "targetAgent", decision.targetAgent(),
                        "confidence", decision.confidence(),
                        "router", "supervisor")));
    }

    private AgentState rejectAndFallback(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink,
            String rejectionCode,
            String reason,
            SupervisorRouteDecision decision) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("rejectionCode", rejectionCode);
        data.put("reason", reason == null ? "" : reason);
        data.put("fallbackAgent", fallbackId);
        data.put("router", "supervisor");
        if (decision != null) {
            data.put("scope", decision.scope().name());
            data.put("targetAgent", decision.targetAgent());
            data.put("confidence", decision.confidence());
        }
        LOGGER.info("[supervisor] rejected sessionId={} code={} reason={}",
                request.sessionId(), rejectionCode, reason);
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.ROUTE_REJECTED,
                request.sessionId(), reason == null ? rejectionCode : reason, data));
        return fallback(request, context, runningState, eventSink);
    }

    private AgentState fallback(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        return fallback.run(request, context.withAgentId(fallbackId), runningState, eventSink);
    }

    private static AgentState clarify(
            AgentRequest request,
            AgentState runningState,
            AgentEventSink eventSink,
            SupervisorRouteDecision decision) {
        String question = decision.clarifyingQuestion();
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.ROUTE_CLARIFICATION_REQUIRED,
                request.sessionId(), question,
                Map.of(
                        "intent", decision.intent(),
                        "scope", decision.scope().name(),
                        "confidence", decision.confidence(),
                        "router", "supervisor")));
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.OUTPUT_DELTA, request.sessionId(), question,
                Map.of("agentId", ID, "sequence", 0, "source", "routing-clarification")));
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_COMPLETED, request.sessionId(), question,
                Map.of("agentId", ID, "router", "supervisor")));
        return runningState.complete(question);
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public AgentState resume(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentCheckpoint checkpoint,
            PendingActionResolution resolution,
            AgentEventSink eventSink) {
        return fallback.resume(request, context, runningState, checkpoint, resolution, eventSink);
    }

    @Override
    public AgentCheckpoint checkpoint(
            AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
        return fallback.checkpoint(request, context, checkpoint);
    }

    @Override
    public void discard(AgentCheckpoint checkpoint) {
        fallback.discard(checkpoint);
    }
}
