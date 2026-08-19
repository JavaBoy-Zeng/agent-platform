package com.github.agentos.agent.loop;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.InstructionProcessor;
import com.github.agentos.planner.flow.LlmFlow;
import com.github.agentos.planner.flow.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 意图分级中的“简单问答”执行 Agent。
 *
 * <p>由 {@link com.github.agentos.agent.routing.IntentClassifier} 判定为
 * simple-qa 的请求派发到这里：单次轻量模型调用直接生成回答，
 * 不携带工具定义、不进入规划循环、不写记忆，最大限度降低首响延迟与 token 成本。
 * 出现失败时直接以失败状态结束，不降级回主 Agent。</p>
 *
 * <p>请求构造委托 {@link LlmFlow} 处理器链：指令注入与会话历史展开
 * 各自独立可插拔，Agent 本身不再拼接 prompt。</p>
 */
public final class SimpleQaAgent implements Agent, AgentLoop {

    /** 注册到 AgentRegistry 的稳定标识，供路由决策引用。 */
    public static final String ID = "simple-qa-agent";

    /** 直答路径的默认系统指令。 */
    private static final String DEFAULT_INSTRUCTION = """
            你是一个高效的中文助手。直接、准确地回答用户问题，不要编造事实。
            如果问题需要实时信息、文件操作或外部工具才能回答，请明确说明你无法获取这类信息，
            并建议用户描述完整任务后重试。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleQaAgent.class);

    private final ChatClient chatClient;
    private final LlmFlow llmFlow;

    /** 创建使用默认指令与历史处理器的简单问答 Agent。 */
    public SimpleQaAgent(ChatClient chatClient) {
        this(chatClient, defaultFlow());
    }

    /** 创建使用自定义请求构造链的简单问答 Agent。 */
    public SimpleQaAgent(ChatClient chatClient, LlmFlow llmFlow) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.llmFlow = Objects.requireNonNull(llmFlow, "llmFlow must not be null");
    }

    private static LlmFlow defaultFlow() {
        return new LlmFlow(List.of(
                new InstructionProcessor(DEFAULT_INSTRUCTION),
                new HistoryProcessor()));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Single-shot direct-answer agent for simple questions routed by intent";
    }

    /** 通过统一 Agent 抽象执行一次直答。 */
    @Override
    public AgentExecutionResult run(AgentRequest request, InvocationContext context) {
        AgentState result = run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
        return AgentExecutionResult.from(
                result, context.invocation() == null ? null : context.invocation().pendingAction());
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
        LOGGER.info("[simple-qa] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID, "router", "direct-chat")));
        try {
            LlmRequest llmRequest = llmFlow.build(request);
            java.util.concurrent.atomic.AtomicInteger deltaSequence = new java.util.concurrent.atomic.AtomicInteger();
            ChatClient.ChatResponse response = chatClient.chatStream(
                    request.sessionId(),
                    llmRequest,
                    delta -> eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.OUTPUT_DELTA,
                            request.sessionId(),
                            delta,
                            Map.of("agentId", ID, "sequence", deltaSequence.incrementAndGet()))));
            String answer = response.answer();
            if (response.usage() != null) {
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.USAGE,
                        request.sessionId(),
                        "模型用量",
                        Map.of(
                                "agentId", ID,
                                "model", response.usage().model(),
                                "promptTokens", response.usage().promptTokens(),
                                "completionTokens", response.usage().completionTokens(),
                                "totalTokens", response.usage().totalTokens())));
            }
            LOGGER.info("[simple-qa] finished sessionId={} answerChars={} durationMs={}",
                    request.sessionId(), answer.length(), (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    answer,
                    Map.of("agentId", ID, "router", "direct-chat")));
            return runningState.complete(answer);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            if (Thread.currentThread().isInterrupted()
                    || exception instanceof java.util.concurrent.CancellationException) {
                LOGGER.info("[simple-qa] cancelled sessionId={}", request.sessionId());
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.RUN_CANCELLED,
                        request.sessionId(),
                        "run cancelled by user",
                        Map.of("agentId", ID, "router", "direct-chat")));
                return runningState.cancel(message);
            }
            LOGGER.warn("[simple-qa] failed sessionId={} error={}",
                    request.sessionId(), message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", ID, "router", "direct-chat")));
            return runningState.fail(message);
        }
    }
}
