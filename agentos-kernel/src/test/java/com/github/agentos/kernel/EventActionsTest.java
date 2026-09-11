package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 事件状态变更指令测试。 */
class EventActionsTest {

    @Test
    void noneIsEmpty() {
        assertThat(EventActions.NONE.isEmpty()).isTrue();
        assertThat(EventActions.NONE.stateDelta()).isEmpty();
        assertThat(EventActions.NONE.transferToAgent()).isNull();
    }

    @Test
    void stateDeltaFactoryOnlyCarriesDelta() {
        EventActions actions = EventActions.stateDelta(Map.of("city", "重庆"));

        assertThat(actions.isEmpty()).isFalse();
        assertThat(actions.stateDelta()).containsEntry("city", "重庆");
        assertThat(actions.endInvocation()).isFalse();
        assertThat(actions.requireApproval()).isFalse();
    }

    @Test
    void approvalFactoryMarksApproval() {
        assertThat(EventActions.approval().requireApproval()).isTrue();
    }

    @Test
    void withStateDeltaMergesIntoExistingDelta() {
        EventActions actions = EventActions.stateDelta(Map.of("a", 1))
                .withStateDelta(Map.of("b", 2));

        assertThat(actions.stateDelta())
                .containsEntry("a", 1)
                .containsEntry("b", 2);
    }

    @Test
    void normalizesNullAndBlankFields() {
        EventActions actions = new EventActions(null, "  ", false, false);

        assertThat(actions.stateDelta()).isEmpty();
        assertThat(actions.transferToAgent()).isNull();
        assertThat(actions.isEmpty()).isTrue();
    }

    @Test
    void defaultEventCarriesActions() {
        InvocationContext context = InvocationContext.of("plan-execute-agent").withInvocation(
                new AgentInvocation("inv-1", "session-1", "plan-execute-agent", "", java.time.Instant.now()));

        DefaultAgentEvent plain = DefaultAgentEvent.of(
                context, AgentEventType.STEP_STARTED, "step", Map.of());
        DefaultAgentEvent withActions = DefaultAgentEvent.of(
                context, AgentEventType.TOOL_CALL_COMPLETED, "done", Map.of(),
                EventActions.stateDelta(Map.of("city", "重庆")));

        assertThat(plain.actions()).isEqualTo(EventActions.NONE);
        assertThat(withActions.actions().stateDelta()).containsEntry("city", "重庆");
        assertThat(new DefaultAgentEvent(
                "e1", "s1", "i1", "a1", java.time.Instant.now(),
                AgentEventType.AGENT_STARTED, "m", Map.of(), null).actions())
                .isEqualTo(EventActions.NONE);
    }
}
