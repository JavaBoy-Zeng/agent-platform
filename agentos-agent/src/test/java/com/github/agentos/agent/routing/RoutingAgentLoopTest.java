package com.github.agentos.agent.routing;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.loop.SimpleQaAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.registry.InMemoryAgentRegistry;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 意图路由器单元测试。 */
class RoutingAgentLoopTest {

    @Test
    void shortCircuitsWithoutInvokingFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AgentLoop fallback = (req, ctx, running) -> {
            fallbackCalls.incrementAndGet();
            return running.complete("fallback-output");
        };
        IntentClassifier classifier = (req, ctx) -> IntentClassification.shortCircuit("pong");
        RoutingAgentLoop router = new RoutingAgentLoop(
                classifier, new InMemoryAgentRegistry(), fallback);

        List<AgentRunEvent> events = new ArrayList<>();
        AgentState result = router.run(
                new AgentRequest("s1", "hi", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("pong");
        assertThat(fallbackCalls.get()).isZero();
        assertThat(events).extracting(AgentRunEvent::type).containsExactly(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.OUTPUT_DELTA,
                AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events.get(1).message()).isEqualTo("pong");
        assertThat(events.get(2).data()).containsEntry("router", "short-circuit");
    }

    @Test
    void routesToRegisteredAgentById() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        EchoAgent echo = new EchoAgent();
        AgentLoop fallback = (req, ctx, running) -> {
            fallbackCalls.incrementAndGet();
            return running.complete("fallback");
        };
        AgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(echo);
        IntentClassifier classifier = (req, ctx) -> IntentClassification.routeTo(
                "echo-agent", "echo");
        RoutingAgentLoop router = new RoutingAgentLoop(classifier, registry, fallback);

        AgentState result = router.run(
                new AgentRequest("s1", "hello", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                event -> { });

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("echo:hello");
        assertThat(echo.calls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isZero();
    }

    @Test
    void dispatchUpdatesContextAndPropagatesClassificationAttributes() {
        AtomicReference<InvocationContext> seenContext = new AtomicReference<>();
        AtomicReference<AgentRequest> seenRequest = new AtomicReference<>();
        AgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(new CapturingAgent(seenRequest, seenContext));
        IntentClassifier classifier = (req, ctx) -> new IntentClassification(
                "capture", "capture-agent", ExecutionMode.REACT, 0.9, null,
                Map.of("routerAttribute", "present"));
        RoutingAgentLoop router = new RoutingAgentLoop(
                classifier, registry, (req, ctx, running) -> running.complete("fallback"));

        AgentState result = router.run(
                new AgentRequest("s1", "hello", Map.of("callerAttribute", "kept")),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP);

        assertThat(result.output()).isEqualTo("captured");
        assertThat(seenContext.get().agentId()).isEqualTo("capture-agent");
        assertThat(seenRequest.get().attributes())
                .containsEntry("routerAttribute", "present")
                .containsEntry("callerAttribute", "kept")
                .containsEntry("executionMode", "REACT");
    }

    @Test
    void fallsBackWhenClassifierReturnsFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        AgentLoop fallback = (req, ctx, running) -> {
            fallbackCalls.incrementAndGet();
            return running.complete("from-fallback");
        };
        IntentClassifier classifier = (req, ctx) -> IntentClassification.fallback("unknown");
        AgentRegistry registry = new InMemoryAgentRegistry();
        RoutingAgentLoop router = new RoutingAgentLoop(classifier, registry, fallback);

        AgentState result = router.run(
                new AgentRequest("s1", "do something", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                event -> { });

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("from-fallback");
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    @Test
    void endToEndRoutesSimpleQaToDirectChatAgent() {
        // 启发式分类器 + 注册的 SimpleQaAgent + PlanExecuteAgent fallback 的组合行为：
        // “什么是 JVM” 应单次直答完成，不触碰 fallback。
        AtomicInteger fallbackCalls = new AtomicInteger();
        AgentLoop fallback = (req, ctx, running) -> {
            fallbackCalls.incrementAndGet();
            return running.complete("from-fallback");
        };
        AgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(new SimpleQaAgent(
                (sessionId, message) -> "JVM 是 Java 虚拟机。"));
        IntentClassifier classifier = new HeuristicIntentClassifier(16, "收到", 64, SimpleQaAgent.ID);
        RoutingAgentLoop router = new RoutingAgentLoop(classifier, registry, fallback);

        AgentState result = router.run(
                new AgentRequest("s1", "什么是 JVM", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                event -> { });

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("JVM 是 Java 虚拟机。");
        assertThat(fallbackCalls.get()).isZero();
    }

    @Test
    void unknownAgentIdIsRejected() {
        AgentLoop fallback = (req, ctx, running) -> running.complete("n/a");
        IntentClassifier classifier = (req, ctx) -> IntentClassification.routeTo(
                "missing-agent", "x");
        AgentRegistry registry = new InMemoryAgentRegistry();
        RoutingAgentLoop router = new RoutingAgentLoop(classifier, registry, fallback);

        assertThatThrownBy(() -> router.run(
                new AgentRequest("s1", "hi", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                AgentEventSink.NOOP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-agent");
    }

    @Test
    void resumeDelegatesToFallback() {
        AtomicInteger fallbackResumeCalls = new AtomicInteger();
        AgentLoop fallback = new AgentLoop() {
            @Override
            public AgentState run(AgentRequest req, InvocationContext ctx, AgentState running) {
                return running.complete("fallback");
            }

            @Override
            public AgentState resume(AgentRequest req, InvocationContext ctx, AgentState running,
                                     com.github.agentos.kernel.AgentCheckpoint checkpoint,
                                     com.github.agentos.kernel.PendingActionResolution resolution,
                                     AgentEventSink sink) {
                fallbackResumeCalls.incrementAndGet();
                return running.complete("resumed");
            }
        };
        IntentClassifier classifier = (req, ctx) -> IntentClassification.fallback("x");
        RoutingAgentLoop router = new RoutingAgentLoop(
                classifier, new InMemoryAgentRegistry(), fallback);

        AgentState result = router.resume(
                new AgentRequest("s1", "hi", Map.of()),
                InvocationContext.of("plan-execute-agent"),
                AgentState.ready().startNextIteration(),
                new com.github.agentos.kernel.AgentCheckpoint(
                        "s1", "inv-1", "plan-execute-agent", "", "team", "user", "hi",
                        "", "", 0, List.of(), Map.of(), null,
                        new com.github.agentos.kernel.ExecutionCounters(0, 0, 0, 0),
                        com.github.agentos.kernel.AgentRunStatus.WAITING,
                        java.time.Instant.now()),
                com.github.agentos.kernel.PendingActionResolution.approved("pa-1"),
                AgentEventSink.NOOP);

        assertThat(result.output()).isEqualTo("resumed");
        assertThat(fallbackResumeCalls.get()).isEqualTo(1);
    }

    @Test
    void intentClassificationValidationRejectsBlankIntent() {
        assertThatThrownBy(() -> new IntentClassification(
                "", null, ExecutionMode.PLAN, 0.5, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intentClassificationValidationRejectsOutOfRangeConfidence() {
        assertThatThrownBy(() -> new IntentClassification(
                "x", null, null, 1.5, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intentClassificationRejectsContradictoryAndNonFiniteValues() {
        assertThatThrownBy(() -> new IntentClassification(
                "x", "agent", null, 0.5, "answer", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentClassification(
                "x", null, null, Double.NaN, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 同时实现 Agent 与 AgentLoop 的回显 Agent，用于验证注册派发路径。 */
    private static final class EchoAgent implements Agent, AgentLoop {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return "echo-agent";
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public AgentExecutionResult run(AgentRequest req, InvocationContext ctx) {
            calls.incrementAndGet();
            return AgentExecutionResult.from(
                    AgentState.ready().complete("agent-echo:" + req.objective()), null);
        }

        @Override
        public AgentState run(AgentRequest req, InvocationContext ctx, AgentState running) {
            return run(req, ctx, running, AgentEventSink.NOOP);
        }

        @Override
        public AgentState run(AgentRequest req, InvocationContext ctx, AgentState running,
                              AgentEventSink sink) {
            calls.incrementAndGet();
            sink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED, req.sessionId(),
                    "echo:" + req.objective(), Map.of("agentId", "echo-agent")));
            return running.complete("echo:" + req.objective());
        }
    }

    private static final class CapturingAgent implements Agent, AgentLoop {
        private final AtomicReference<AgentRequest> request;
        private final AtomicReference<InvocationContext> context;

        private CapturingAgent(
                AtomicReference<AgentRequest> request,
                AtomicReference<InvocationContext> context) {
            this.request = request;
            this.context = context;
        }

        @Override
        public String id() {
            return "capture-agent";
        }

        @Override
        public String description() {
            return "captures routed input";
        }

        @Override
        public AgentExecutionResult run(AgentRequest req, InvocationContext ctx) {
            return AgentExecutionResult.from(
                    AgentState.ready().complete("captured"), null);
        }

        @Override
        public AgentState run(AgentRequest req, InvocationContext ctx, AgentState running) {
            request.set(req);
            context.set(ctx);
            return running.complete("captured");
        }
    }
}
