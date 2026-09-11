package com.github.agentos.agent.workflow;

import com.github.agentos.agent.finalize.DefaultAgentFinalizer;
import com.github.agentos.agent.loop.*;
import com.github.agentos.hitl.*;
import com.github.agentos.kernel.*;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.*;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDelegationResumeTest {
    private final Map<String, String> persisted = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContinuationStore store = new ContinuationStore() {
        public void save(String id, PersistedContinuation value) { persisted.put(id, mapper.writeValueAsString(value)); }
        public Optional<PersistedContinuation> load(String id) {
            return Optional.ofNullable(persisted.get(id)).map(json -> mapper.readValue(json, PersistedContinuation.class));
        }
        public void delete(String id) { persisted.remove(id); }
    };
    private final CheckpointStore checkpoints = new InMemoryCheckpointStore();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger planned = new AtomicInteger();
    private final AgentExecutionLimits limits = new AgentExecutionLimits(3, 20, 20, 8);
    private final AgentTool write = new AgentTool() {
        public String name() { return "file_write"; }
        public String description() { return "write"; }
        public RiskLevel riskLevel() { return RiskLevel.HIGH; }
        public ToolResult execute(ToolContext context, ToolCall call) {
            writes.incrementAndGet();
            return ToolResult.success(call.arguments().get("path"));
        }
    };

    private AgentLoop parent(String mode, MemoryService memory) {
        ResumableSpecialist child = new ResumableSpecialist("writer-agent", "two writes") {
            protected AgentState runWorkflow(AgentRequest request, InvocationContext context,
                    AgentState state, AgentEventSink sink) {
                String content = modelResult("content", context, () -> "draft-" + generated.incrementAndGet());
                for (String path : List.of("first.md", "second.md")) {
                    ToolResult result = dispatchTool(write,
                            new ToolCall("file_write", Map.of("path", path, "content", content)), request, context);
                    if (!result.success()) return state.fail(result.error());
                }
                return state.complete("written");
            }
        };
        var adapter = new AgentToolAdapter(child, AgentTool.RiskLevel.LOW, checkpoints);
        var registry = new ToolRegistry(List.of(write, adapter), Set.of("plan-execute-agent", "react-agent"));
        var dispatcher = new ToolDispatcher(registry, List.of(new ApprovalToolInterceptor(
                new RiskPolicy(AgentTool.RiskLevel.HIGH), new ApprovalService(request -> false))));
        child.configureExecution(dispatcher, store, limits);
        if (mode.equals("react")) {
            ChatClient chat = new ChatClient() {
                public String chat(String session, LlmRequest request) { throw new AssertionError(); }
                public ToolCallResponse chatWithTools(String session, LlmRequest request, Consumer<String> delta) {
                    if (planned.incrementAndGet() == 1) {
                        return ToolCallResponse.call(new ToolCall("writer-agent", Map.of("objective", "write both")),
                                "call-1", null);
                    }
                    return ToolCallResponse.answer("done", null);
                }
            };
            return new ReactAgent(chat, dispatcher, List.of(adapter), limits,
                    new ConversationCompactor(24000, 4000, 600), store, 0, 0);
        }
        AgentPlanner planner = new AgentPlanner() {
            public AgentPlan createPlan(AgentRequest request, InvocationContext context) {
                planned.incrementAndGet();
                return AgentPlan.create(PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE,
                        "write", List.of(new PlanStep("delegate", "write", false,
                                new ToolCall("writer-agent", Map.of("objective", "write both")))), null);
            }
            public AgentPlan replan(AgentRequest request, InvocationContext context, AgentPlan previous,
                    PlanExecutionSnapshot snapshot) {
                return AgentPlan.create(PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                        "done", List.of(), "done");
            }
        };
        return new PlanExecuteAgent(planner, new PlanExecutor(dispatcher, new DefaultFailureClassifier()), memory,
                new DefaultAgentFinalizer(), limits, new DefaultObservationSummarizer(), store);
    }

    @ParameterizedTest @ValueSource(strings = {"plan", "react"})
    void restoresParentAndChildAcrossTwoApprovalsAndFreshInstances(String mode) {
        try (MemoryService memory = MemoryService.inMemory()) {
            var request = AgentRequest.of("s1", "write both");
            String id = mode.equals("plan") ? "plan-execute-agent" : "react-agent";
            var invocation = new AgentInvocation("root", "s1", id, "", Instant.now());
            var context = InvocationContext.of(id).withInvocation(invocation);
            AgentLoop loop = parent(mode, memory);
            AgentState state = loop.run(request, context, running(), AgentEventSink.NOOP);
            assertThat(state.status()).isEqualTo(AgentState.Status.WAITING);
            assertThat(writes).hasValue(0);
            String childId = String.valueOf(invocation.pendingAction().payload().get("delegationInvocation"));
            for (int approval = 1; approval <= 2; approval++) {
                var checkpoint = loop.checkpoint(request, context, checkpoint(context, request));
                // JSON roundtrip + new loop/adapter/specialist instances simulate restart.
                checkpoint = mapper.readValue(mapper.writeValueAsString(checkpoint), AgentCheckpoint.class);
                loop = parent(mode, memory);
                var resolution = PendingActionResolution.approved(checkpoint.pendingAction().pendingActionId());
                invocation.waitFor(checkpoint.pendingAction());
                invocation.resolve(resolution);
                state = loop.resume(request, context, running(), checkpoint, resolution, AgentEventSink.NOOP);
                assertThat(writes).hasValue(approval);
                assertThat(generated).hasValue(1);
                assertThat(state.status()).isEqualTo(approval == 1
                        ? AgentState.Status.WAITING : AgentState.Status.COMPLETED);
            }
            assertThat(checkpoints.load(childId)).isEmpty();
            assertThat(persisted).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plan", "react"})
    void rejectionDiscardsChildWithoutWriting(String mode) {
        try (MemoryService memory = MemoryService.inMemory()) {
            var request = AgentRequest.of("s1", "write");
            String id = mode.equals("plan") ? "plan-execute-agent" : "react-agent";
            var context = InvocationContext.of(id).withInvocation(new AgentInvocation("root", "s1", id, "", Instant.now()));
            var loop = parent(mode, memory);
            loop.run(request, context, running(), AgentEventSink.NOOP);
            String childId = String.valueOf(context.invocation().pendingAction().payload().get("delegationInvocation"));
            loop.discard(loop.checkpoint(request, context, checkpoint(context, request)));
            assertThat(checkpoints.load(childId)).isEmpty();
            assertThat(persisted).isEmpty();
            assertThat(writes).hasValue(0);
        }
    }

    @Test void codeSpecialistPreservesGeneratedCodeAndRepairAcrossApprovals() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool command = new AgentTool() {
            public String name() { return "run_command"; }
            public String description() { return "run code"; }
            public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            public ToolResult execute(ToolContext context, ToolCall call) {
                return executions.incrementAndGet() == 1
                        ? ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, "repair required")
                        : ToolResult.success("ok");
            }
        };
        ChatClient chat = (session, request) -> "# python\nprint('" + generated.incrementAndGet() + "')";
        var dispatcher = new ToolDispatcher(new ToolRegistry(List.of(write, command)),
                List.of(new ApprovalToolInterceptor(new RiskPolicy(AgentTool.RiskLevel.HIGH),
                        new ApprovalService(request -> false))));
        java.util.function.Supplier<com.github.agentos.agent.specialist.CodeAgent> fresh = () -> {
            var agent = new com.github.agentos.agent.specialist.CodeAgent(chat, write, command);
            agent.configureExecution(dispatcher, store, limits);
            return agent;
        };
        var request = AgentRequest.of("s1", "generate and repair");
        var context = InvocationContext.of("code-agent").withInvocation(
                new AgentInvocation("code-root", "s1", "code-agent", "", Instant.now()));
        var agent = fresh.get();
        var state = agent.run(request, context, running(), AgentEventSink.NOOP);
        assertThat(state.status()).isEqualTo(AgentState.Status.WAITING);
        for (int n = 0; n < 4; n++) {
            var saved = checkpoint(context, request);
            var resolution = PendingActionResolution.approved(saved.pendingAction().pendingActionId());
            context.invocation().resolve(resolution);
            agent = fresh.get();
            state = agent.resume(request, context, running(), saved, resolution, AgentEventSink.NOOP);
            assertThat(state.status()).isEqualTo(n < 3 ? AgentState.Status.WAITING : AgentState.Status.COMPLETED);
        }
        assertThat(writes).hasValue(2);
        assertThat(executions).hasValue(2);
        assertThat(generated).hasValue(2);
        assertThat(persisted).isEmpty();
    }

    @Test void dispatcherRejectsRawToolsEvenWhenModelInventsThem() {
        var registry = new ToolRegistry(List.of(write), Set.of("plan-execute-agent", "react-agent"));
        var dispatcher = new ToolDispatcher(registry);
        for (String id : List.of("plan-execute-agent", "react-agent")) {
            var context = InvocationContext.of(id);
            assertThat(registry.getTools(context)).isEmpty();
            var result = dispatcher.dispatch(new ToolCall("file_write", Map.of("path", "x")),
                    tool -> new ToolContext(AgentRequest.of("s1", "write"), context, "", "", limits, Map.of(), tool));
            assertThat(result.failureType()).isEqualTo(ToolFailureType.PERMISSION_DENIED);
        }
        assertThat(writes).hasValue(0);
    }

    private static AgentState running() { return AgentState.ready().startNextIteration(); }
    private static AgentCheckpoint checkpoint(InvocationContext context, AgentRequest request) {
        var invocation = context.invocation();
        return new AgentCheckpoint(request.sessionId(), invocation.invocationId(), context.agentId(), "",
                context.teamId(), context.userId(), request.objective(), "", "", 0, List.of(), Map.of(),
                invocation.pendingAction(), new ExecutionCounters(invocation.modelCalls(), invocation.toolCalls(),
                invocation.replans(), invocation.steps()), AgentRunStatus.WAITING, Instant.now());
    }
}
