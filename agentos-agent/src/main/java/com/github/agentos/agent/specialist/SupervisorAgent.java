package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 监督 Agent：轻量 LLM 路由层，在 SimpleQaAgent（直答）和 MainAgent（全量规划）之间。
 *
 * <p>使用单次 LLM 调用将任务分类到最合适的专业 Agent（search-agent / code-agent /
 * report-agent），直接派发执行，跳过完整规划循环以降低延迟和 token 成本。
 * 如果任务需要多步骤组合或无法明确分类，回退到 fallback（通常是 MainAgent），
 * 由规划器把各专业 Agent 当工具编排。</p>
 *
 * <p>路由层级：</p>
 * <pre>
 * 问候短路 → SimpleQaAgent（单轮直答）
 *          → SupervisorAgent（单次 LLM 分类 + 直接派发）
 *                      → MainAgent（多步规划 + 工具编排，含 AgentToolAdapter）
 * </pre>
 */
public final class SupervisorAgent implements Agent, AgentLoop {

    /** 注册标识。 */
    public static final String ID = "supervisor-agent";

    private static final String CLASSIFY_INSTRUCTION = """
            你是任务分类器。根据用户目标判断应该交给哪个专家处理：
            - search-agent：需要搜索网络信息、查找资料、获取最新数据
            - code-agent：需要编写和执行代码、运行脚本
            - report-agent：需要生成文档、报告、技术文档
            - main-agent：需要多步骤组合、复杂规划或无法归类

            只返回 Agent ID（如 search-agent），不要解释。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(SupervisorAgent.class);

    private final ChatClient chatClient;
    private final Map<String, Agent> specialists;
    private final AgentLoop fallback;

    /**
     * 创建监督 Agent。
     *
     * @param chatClient 用于任务分类的轻量模型客户端
     * @param specialists 专业 Agent 映射（key = Agent ID，value = Agent 实例）
     * @param fallback 回退循环（通常是 MainAgent）
     */
    public SupervisorAgent(
            ChatClient chatClient,
            Map<String, Agent> specialists,
            AgentLoop fallback) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.specialists = Map.copyOf(Objects.requireNonNull(specialists, "specialists must not be null"));
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "LLM-based task router that dispatches to specialist agents";
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
        LOGGER.info("[supervisor] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID)));

        try {
            // 单次 LLM 分类
            LlmRequest classifyRequest = new LlmRequest(CLASSIFY_INSTRUCTION,
                    List.of(LlmMessage.user(request.objective())));
            String targetId = chatClient.chat(request.sessionId(), classifyRequest).trim();
            LOGGER.info("[supervisor] classified sessionId={} target={}", request.sessionId(), targetId);

            // 直接派发到专业 Agent，或回退到 MainAgent
            // 注意：不使用 DECISION 事件类型，该类型专属于 MainAgent 的 plan outcome（含 outcome 字段），
            // 否则会污染 DECISION 事件流的语义。路由决策通过 RUN_STARTED + 日志即可观测。
            if ("main-agent".equals(targetId) || !specialists.containsKey(targetId)) {
                LOGGER.info("[supervisor] falling back to main-agent sessionId={} target={}",
                        request.sessionId(), targetId);
                InvocationContext childContext = context.withAgentId("main-agent");
                return fallback.run(request, childContext, runningState, eventSink);
            }

            Agent specialist = specialists.get(targetId);
            if (!(specialist instanceof AgentLoop specialistLoop)) {
                return runningState.fail("specialist agent does not implement AgentLoop: " + targetId);
            }

            LOGGER.info("[supervisor] dispatching sessionId={} target={}",
                    request.sessionId(), targetId);
            InvocationContext childContext = context.withAgentId(targetId);
            return specialistLoop.run(request, childContext, runningState, eventSink);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[supervisor] failed sessionId={} error={}",
                    request.sessionId(), message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", ID)));
            return runningState.fail(message);
        }
    }

    /** 恢复执行时透传到 fallback（MainAgent），因为挂起只发生在规划循环内部。 */
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

    /** 检查点查询透传到 fallback。 */
    @Override
    public AgentCheckpoint checkpoint(
            AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
        return fallback.checkpoint(request, context, checkpoint);
    }

    /** 丢弃检查点透传到 fallback。 */
    @Override
    public void discard(AgentCheckpoint checkpoint) {
        fallback.discard(checkpoint);
    }
}
