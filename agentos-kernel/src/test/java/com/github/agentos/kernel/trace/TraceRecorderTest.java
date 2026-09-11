package com.github.agentos.kernel.trace;

import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.AgentInvocation;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.ModelUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecorderTest {

    @Test
    void fullRun_buildsRootAndChildSpans() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        String invocationId = "inv-1";
        AgentInvocation invocation = new AgentInvocation(
                invocationId, "session-1", "plan-execute-agent", "task-1", Instant.now());
        InvocationContext context = InvocationContext.of("plan-execute-agent")
                .withInvocation(invocation);
        AgentRequest request = AgentRequest.of("session-1", "tell me the weather");

        recorder.beforeRun(request, context);

        recorder.onEvent(event(context, AgentEventType.PLAN_CREATED, "plan ready", Map.of()));
        recorder.onEvent(event(context, AgentEventType.MODEL_CALL_STARTED, "model start", Map.of()));
        recorder.onModelUsage("session-1", new ModelUsage("gpt-4", 100, 200));
        recorder.onEvent(event(context, AgentEventType.MODEL_CALL_COMPLETED, "model done", Map.of("outcome", "OK")));
        recorder.onEvent(event(context, AgentEventType.TOOL_CALL_STARTED, "tool start", Map.of("toolName", "weather")));
        recorder.onEvent(event(context, AgentEventType.TOOL_CALL_COMPLETED, "tool done", Map.of("toolName", "weather", "success", true)));
        recorder.onEvent(event(context, AgentEventType.STEP_STARTED, "step start", Map.of()));
        recorder.onEvent(event(context, AgentEventType.STEP_COMPLETED, "step done", Map.of()));

        AgentState result = AgentState.ready().complete("28℃ sunny");
        recorder.afterRun(request, context, result);

        List<Span> spans = store.findByTraceId(invocationId);
        assertThat(spans).hasSize(4);

        Span root = spans.stream().filter(s -> s.kind() == Span.Kind.ROOT).findFirst().orElseThrow();
        assertThat(root.status()).isEqualTo(Span.Status.OK);
        assertThat(root.attributes()).containsEntry("agentId", "plan-execute-agent");
        assertThat(root.attributes()).containsEntry("finalStatus", "COMPLETED");
        assertThat(root.attributes()).containsEntry("output", "28℃ sunny");
        assertThat(root.parentSpanId()).isEmpty();

        Span model = spans.stream().filter(s -> s.kind() == Span.Kind.MODEL).findFirst().orElseThrow();
        assertThat(model.status()).isEqualTo(Span.Status.OK);
        assertThat(model.attributes()).containsEntry("model", "gpt-4");
        assertThat(model.attributes()).containsEntry("totalTokens", 300L);
        assertThat(model.parentSpanId()).isEqualTo(root.spanId());

        Span tool = spans.stream().filter(s -> s.kind() == Span.Kind.TOOL).findFirst().orElseThrow();
        assertThat(tool.name()).isEqualTo("tool-call:weather");
        assertThat(tool.status()).isEqualTo(Span.Status.OK);

        Span step = spans.stream().filter(s -> s.kind() == Span.Kind.STEP).findFirst().orElseThrow();
        assertThat(step.status()).isEqualTo(Span.Status.OK);
    }

    @Test
    void failedRun_recordsErrorSpan() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        AgentInvocation invocation = new AgentInvocation("inv-2", "s1", "a1", "", Instant.now());
        InvocationContext context = InvocationContext.of("a1").withInvocation(invocation);
        AgentRequest request = AgentRequest.of("s1", "do stuff");

        recorder.beforeRun(request, context);
        recorder.onEvent(event(context, AgentEventType.MODEL_CALL_STARTED, "start", Map.of()));
        recorder.onEvent(event(context, AgentEventType.MODEL_CALL_FAILED, "boom", Map.of()));

        AgentState failed = AgentState.ready().fail("model error");
        recorder.afterRun(request, context, failed);

        List<Span> spans = store.findByTraceId("inv-2");
        assertThat(spans).hasSize(2);
        Span root = spans.stream().filter(s -> s.kind() == Span.Kind.ROOT).findFirst().orElseThrow();
        assertThat(root.status()).isEqualTo(Span.Status.ERROR);
        assertThat(root.attributes()).containsEntry("finalStatus", "FAILED");
        assertThat(root.attributes()).containsEntry("error", "model error");

        Span model = spans.stream().filter(s -> s.kind() == Span.Kind.MODEL).findFirst().orElseThrow();
        assertThat(model.status()).isEqualTo(Span.Status.ERROR);
    }

    @Test
    void onRunError_recordsErrorAttributes() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        AgentInvocation invocation = new AgentInvocation("inv-3", "s1", "a1", "", Instant.now());
        InvocationContext context = InvocationContext.of("a1").withInvocation(invocation);
        AgentRequest request = AgentRequest.of("s1", "fail");

        recorder.beforeRun(request, context);
        recorder.onRunError(request, context, new RuntimeException("connection refused"));
        recorder.afterRun(request, context, AgentState.ready().fail("crashed"));

        Span root = store.findByTraceId("inv-3").stream()
                .filter(s -> s.kind() == Span.Kind.ROOT).findFirst().orElseThrow();
        assertThat(root.attributes()).containsEntry("runError", "connection refused");
        assertThat(root.attributes()).containsEntry("errorType", "RuntimeException");
    }

    @Test
    void beforeRunWithoutInvocation_isNoop() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        InvocationContext context = InvocationContext.of("a1");
        AgentRequest request = AgentRequest.of("s1", "test");

        recorder.beforeRun(request, context);
        recorder.afterRun(request, context, AgentState.ready().complete("ok"));

        assertThat(store.findByTraceId("")).isEmpty();
    }

    @Test
    void onEventWithoutActiveSpan_isNoop() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        // No beforeRun called → no open span
        recorder.onEvent(new DefaultAgentEvent(
                "e1", "s1", "inv-x", "a1", Instant.now(),
                AgentEventType.MODEL_CALL_STARTED, "start", Map.of()));

        assertThat(store.findByTraceId("inv-x")).isEmpty();
    }

    @Test
    void humanActionEvents_recordedOnTopSpan() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder recorder = new TraceRecorder(store);

        AgentInvocation invocation = new AgentInvocation("inv-4", "s1", "a1", "", Instant.now());
        InvocationContext context = InvocationContext.of("a1").withInvocation(invocation);
        AgentRequest request = AgentRequest.of("s1", "approve");

        recorder.beforeRun(request, context);
        recorder.onEvent(event(context, AgentEventType.HUMAN_ACTION_REQUIRED, "need approval", Map.of()));
        recorder.onEvent(event(context, AgentEventType.HUMAN_ACTION_RESOLVED, "approved", Map.of()));
        recorder.afterRun(request, context, AgentState.ready().waitForAction("waiting"));

        Span root = store.findByTraceId("inv-4").get(0);
        assertThat(root.events()).hasSize(2);
        assertThat(root.events().values().toString()).contains("await-approval");
        assertThat(root.events().values().toString()).contains("approval-resolved");
    }

    @Test
    void name_isTraceRecorder() {
        assertThat(new TraceRecorder(new InMemoryTraceStore()).name()).isEqualTo("TraceRecorder");
    }

    private static DefaultAgentEvent event(
            InvocationContext context, AgentEventType type, String message, Map<String, Object> data) {
        return DefaultAgentEvent.of(context, type, message, data);
    }
}
