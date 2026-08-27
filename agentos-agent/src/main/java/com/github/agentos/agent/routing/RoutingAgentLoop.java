package com.github.agentos.agent.routing;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingActionResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * 在请求进入 {@link AgentLoop} 主体之前执行意图识别和派发的路由器。
 *
 * <p>判定优先级：</p>
 * <ol>
 *   <li>{@link IntentClassification#isShortCircuit()} ⇒ 直接以终态返回，跳过 LLM 与工具调用；</li>
 *   <li>{@link IntentClassification#hasAgentTarget()} ⇒ 通过 {@link AgentRegistry} 派发到指定 Agent；</li>
 *   <li>其余情况 ⇒ 落到 {@code fallback}，沿用既有执行链路（当前为 {@code MainAgent}）。</li>
 * </ol>
 *
 * <p>注册的 Agent 同时承担可执行循环职责，因此必须实现 {@link AgentLoop}；
 * 主 Agent 已满足该不变量。</p>
 */
public final class RoutingAgentLoop implements AgentLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingAgentLoop.class);

    private final IntentClassifier classifier;
    private final AgentRegistry registry;
    private final AgentLoop fallback;

    /** 创建意图路由器。 */
    public RoutingAgentLoop(
            IntentClassifier classifier,
            AgentRegistry registry,
            AgentLoop fallback) {
        this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
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
        Objects.requireNonNull(runningState, "runningState must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");

        IntentClassification classification = Objects.requireNonNull(
                classifier.classify(request, context), "classifier returned null");

        if (classification.isShortCircuit()) {
            return shortCircuit(request, classification, runningState, eventSink);
        }
        if (classification.hasAgentTarget()) {
            return dispatch(request, context, classification, runningState, eventSink);
        }
        LOGGER.debug(
                "intent router falls back sessionId={} intent={} confidence={}",
                request.sessionId(), classification.intent(), classification.confidence());
        return fallback.run(
                routedRequest(request, classification), context, runningState, eventSink);
    }

    @Override
    public AgentState resume(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentCheckpoint checkpoint,
            PendingActionResolution resolution,
            AgentEventSink eventSink) {
        // 路由器本身不持有恢复位置，恢复阶段直接透传到 fallback。
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

    private AgentState shortCircuit(
            AgentRequest request,
            IntentClassification classification,
            AgentState runningState,
            AgentEventSink eventSink) {
        String answer = classification.directAnswer();
        LOGGER.info(
                "[agent-router] short-circuit sessionId={} intent={} confidence={} messageLength={}",
                request.sessionId(),
                classification.intent(),
                classification.confidence(),
                answer.length());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                "router short-circuit",
                Map.of("intent", classification.intent(), "router", "short-circuit")));
        if (classification.intent().endsWith("-clarification")) {
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.ROUTE_CLARIFICATION_REQUIRED,
                    request.sessionId(), answer,
                    Map.of(
                            "intent", classification.intent(),
                            "confidence", classification.confidence(),
                            "router", "heuristic")));
        }
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.OUTPUT_DELTA,
                request.sessionId(),
                answer,
                Map.of(
                        "intent", classification.intent(),
                        "router", "short-circuit",
                        "sequence", 0,
                        "source", "runtime-result")));
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_COMPLETED,
                request.sessionId(),
                answer,
                Map.of(
                        "intent", classification.intent(),
                        "router", "short-circuit",
                        "confidence", classification.confidence())));
        return runningState.complete(answer);
    }

    private AgentState dispatch(
            AgentRequest request,
            InvocationContext context,
            IntentClassification classification,
            AgentState runningState,
            AgentEventSink eventSink) {
        String agentId = Objects.requireNonNull(classification.agentId(), "agentId must not be null");
        Agent target = registry.find(agentId).orElseThrow(() -> new IllegalArgumentException(
                "intent router resolved unknown agentId: " + agentId));
        if (!(target instanceof AgentLoop targetLoop)) {
            throw new IllegalStateException(
                    "registered agent must implement AgentLoop: " + agentId);
        }
        LOGGER.info(
                "[agent-router] dispatch sessionId={} intent={} agentId={} confidence={}",
                request.sessionId(),
                classification.intent(),
                agentId,
                classification.confidence());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.ROUTE_DECIDED,
                request.sessionId(),
                "请求已路由到 " + agentId,
                Map.of(
                        "intent", classification.intent(),
                        "targetAgent", agentId,
                        "confidence", classification.confidence(),
                        "router", "heuristic")));
        return targetLoop.run(
                routedRequest(request, classification),
                context.withAgentId(agentId),
                runningState,
                eventSink);
    }

    /**
     * 将分类器给出的执行模式与扩展属性显式传入后续执行链。
     *
     * <p>调用方原始属性优先保留；路由属性用于补充分类阶段产生的信息，
     * executionMode 则属于明确的路由决策，覆盖同名请求属性。</p>
     */
    private static AgentRequest routedRequest(
            AgentRequest request, IntentClassification classification) {
        if (classification.attributes().isEmpty() && classification.executionMode() == null) {
            return request;
        }
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(
                classification.attributes());
        attributes.putAll(request.attributes());
        if (classification.executionMode() != null) {
            attributes.put("executionMode", classification.executionMode().name());
        }
        return new AgentRequest(request.sessionId(), request.objective(), attributes);
    }
}
