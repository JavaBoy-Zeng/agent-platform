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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * 意图分级中的“简单问答”执行 Agent。
 *
 * <p>由 {@link com.github.agentos.agent.routing.IntentClassifier} 判定为
 * simple-qa 的请求派发到这里：单次轻量模型调用直接生成回答，
 * 不携带工具定义、不进入规划循环、不写记忆，最大限度降低首响延迟与 token 成本。
 * 出现失败时直接以失败状态结束，不降级回主 Agent。</p>
 */
public final class SimpleQaAgent implements Agent, AgentLoop {

    /** 注册到 AgentRegistry 的稳定标识，供路由决策引用。 */
    public static final String ID = "simple-qa-agent";

    /**
     * {@link AgentRequest#attributes()} 中携带会话历史的键。
     *
     * <p>值为从早到晚排列的“用户/助手”多行文本；直答路径用它理解
     * “那明天呢”这类依赖上一轮的指代。服务端在进入运行时前注入。</p>
     */
    public static final String CONVERSATION_HISTORY_ATTRIBUTE = "conversationHistory";

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleQaAgent.class);

    private final ChatClient chatClient;

    /** 创建简单问答 Agent。 */
    public SimpleQaAgent(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
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
            java.util.concurrent.atomic.AtomicInteger deltaSequence = new java.util.concurrent.atomic.AtomicInteger();
            ChatClient.ChatResponse response = chatClient.chatStream(
                    request.sessionId(),
                    directAnswerPrompt(request),
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

    /**
     * 构造直答 prompt：携带会话历史时把历史与当前问题拼接成单条用户消息，
     * 让轻量直答模型也能解析上一轮的指代；无历史时保持原样，零额外 token。
     */
    private static String directAnswerPrompt(AgentRequest request) {
        Object history = request.attributes().get(CONVERSATION_HISTORY_ATTRIBUTE);
        if (history instanceof String text && !text.isBlank()) {
            return "以下是当前会话之前的对话记录（从早到晚）：\n"
                    + text
                    + "\n\n请结合以上对话记录理解当前问题中的指代并直接回答。\n当前问题："
                    + request.objective();
        }
        return request.objective();
    }
}
