package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Checkpoint 与审批恢复测试。 */
class AgentRuntimeResumeTest {

    @Test
    void resumesApprovedInvocationWithSameIdAndRejectStops() {
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger stepOneExecutions = new AtomicInteger();
        AgentLoop loop = new AgentLoop() {
            @Override public AgentState run(
                    AgentRequest request, AgentContext context, AgentState running) {
                return running;
            }

            @Override public AgentState run(
                    AgentRequest request, AgentContext context, AgentState running,
                    AgentEventSink sink) {
                stepOneExecutions.incrementAndGet();
                context.invocation().waitFor(action("approval-1"));
                return running.waitForAction("approval");
            }

            @Override public AgentCheckpoint checkpoint(
                    AgentRequest request, AgentContext context, AgentCheckpoint checkpoint) {
                return new AgentCheckpoint(
                        checkpoint.sessionId(), checkpoint.invocationId(), checkpoint.agentId(),
                        checkpoint.taskId(), checkpoint.teamId(), checkpoint.userId(),
                        checkpoint.objective(), "plan-1", "step-2", 1,
                        java.util.List.of("step-1"), checkpoint.state(),
                        checkpoint.pendingAction(), checkpoint.executionCounters(),
                        checkpoint.status(), checkpoint.savedAt());
            }

            @Override public AgentState resume(
                    AgentRequest request, AgentContext context, AgentState running,
                    AgentCheckpoint checkpoint, PendingActionResolution resolution,
                AgentEventSink sink) {
                assertThat(checkpoint.currentStepIndex()).isEqualTo(1);
                assertThat(checkpoint.completedStepIds()).containsExactly("step-1");
                resumes.incrementAndGet();
                return running.complete("resumed");
            }
        };
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentRuntime runtime = new AgentRuntime(loop, AgentEventPublisher.NOOP, store);

        runtime.run(AgentRequest.of("session-1", "write"), AgentContext.of("main-agent"));
        String invocationId = runtime.latestInvocation("session-1").orElseThrow().invocationId();
        AgentState approved = runtime.resume(
                invocationId, PendingActionResolution.approved("approval-1"));

        assertThat(approved.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(approved.output()).isEqualTo("resumed");
        assertThat(resumes).hasValue(1);
        assertThat(stepOneExecutions).hasValue(1);
        assertThat(runtime.checkpoint(invocationId)).isEmpty();

        runtime.run(AgentRequest.of("session-2", "write"), AgentContext.of("main-agent"));
        String rejectedId = runtime.latestInvocation("session-2").orElseThrow().invocationId();
        AgentState rejected = runtime.resume(
                rejectedId, PendingActionResolution.rejected("approval-1"));
        assertThat(rejected.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(resumes).hasValue(1);
        assertThat(stepOneExecutions).hasValue(2);
    }

    private static PendingAction action(String id) {
        return new PendingAction(
                id, PendingActionType.HUMAN_APPROVAL, "approve", "approve tool", Map.of());
    }
}
