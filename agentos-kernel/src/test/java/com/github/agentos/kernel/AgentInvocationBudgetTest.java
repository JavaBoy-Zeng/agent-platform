package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class AgentInvocationBudgetTest {
    @Test
    void parallelChildrenShareOneLimitAndLeaveRootAFinalCall() {
        var root = invocation("root");
        root.configureModelCallBudget(10, 2);
        long reserved = IntStream.range(0, 100).parallel().filter(i -> {
            var child = invocation("child-" + i);
            child.shareModelCallBudget(root);
            child.configureModelCallBudget(100, 0);
            assertThat(child.modelUsageSessionId()).isEqualTo("root");
            return child.reserveSpecialistModelCall();
        }).count();
        assertThat(reserved).isEqualTo(7);
        assertThat(root.reserveModelCall()).isTrue();
        assertThat(root.reserveModelCall()).isFalse();
        assertThat(root.totalModelCalls()).isEqualTo(10);
    }

    @Test
    void restoredBudgetCannotBeResetByNewDelegationOrCheckpointReplay() {
        var root = invocation("root");
        root.configureModelCallBudget(5, 3);
        var child = invocation("child");
        child.shareModelCallBudget(root);
        assertThat(child.reserveSpecialistModelCall()).isTrue();
        root.configureModelCallBudget(5, 3);
        assertThat(child.reserveSpecialistModelCall()).isFalse();
        assertThat(root.reserveModelCall()).isTrue();
        assertThat(root.remainingModelCalls()).isZero();
    }

    private static AgentInvocation invocation(String id) {
        return new AgentInvocation(id, id, "agent", "", Instant.now());
    }
}
