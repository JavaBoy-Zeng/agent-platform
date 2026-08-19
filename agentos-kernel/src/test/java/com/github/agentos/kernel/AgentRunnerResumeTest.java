package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Checkpoint 与审批恢复测试。 */
class AgentRunnerResumeTest {

    @Test
    void resumesApprovedInvocationWithSameIdAndRejectStops() {
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger stepOneExecutions = new AtomicInteger();
        AgentLoop loop = new AgentLoop() {
            @Override public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState running) {
                return running;
            }

            @Override public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState running,
                    AgentEventSink sink) {
                stepOneExecutions.incrementAndGet();
                context.invocation().waitFor(action("approval-1"));
                return running.waitForAction("approval");
            }

            @Override public AgentCheckpoint checkpoint(
                    AgentRequest request, InvocationContext context, AgentCheckpoint checkpoint) {
                return new AgentCheckpoint(
                        checkpoint.sessionId(), checkpoint.invocationId(), checkpoint.agentId(),
                        checkpoint.taskId(), checkpoint.teamId(), checkpoint.userId(),
                        checkpoint.objective(), "plan-1", "step-2", 1,
                        java.util.List.of("step-1"), checkpoint.state(),
                        checkpoint.pendingAction(), checkpoint.executionCounters(),
                        checkpoint.status(), checkpoint.savedAt());
            }

            @Override public AgentState resume(
                    AgentRequest request, InvocationContext context, AgentState running,
                    AgentCheckpoint checkpoint, PendingActionResolution resolution,
                AgentEventSink sink) {
                assertThat(checkpoint.currentStepIndex()).isEqualTo(1);
                assertThat(checkpoint.completedStepIds()).containsExactly("step-1");
                resumes.incrementAndGet();
                return running.complete("resumed");
            }
        };
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentRunner runner = new AgentRunner(loop, AgentEventPublisher.NOOP, store);

        runner.run(AgentRequest.of("session-1", "write"), InvocationContext.of("main-agent"));
        String invocationId = runner.latestInvocation("session-1").orElseThrow().invocationId();
        AgentState approved = runner.resume(
                invocationId, PendingActionResolution.approved("approval-1"));

        assertThat(approved.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(approved.output()).isEqualTo("resumed");
        assertThat(resumes).hasValue(1);
        assertThat(stepOneExecutions).hasValue(1);
        assertThat(runner.checkpoint(invocationId)).isEmpty();

        runner.run(AgentRequest.of("session-2", "write"), InvocationContext.of("main-agent"));
        String rejectedId = runner.latestInvocation("session-2").orElseThrow().invocationId();
        AgentState rejected = runner.resume(
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
