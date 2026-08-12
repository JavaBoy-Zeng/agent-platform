package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Invocation 执行边界测试。 */
class AgentRuntimeInvocationTest {

    @Test
    void createsDifferentInvocationIdsForTwoRunsInSameSession() {
        List<AgentContext> contexts = new ArrayList<>();
        AgentRuntime runtime = new AgentRuntime((request, context, running) -> {
            contexts.add(context);
            return running.complete("ok");
        });
        AgentRequest request = AgentRequest.of("same-session", "test");

        runtime.run(request, AgentContext.of("main-agent"));
        String firstId = runtime.latestInvocation("same-session").orElseThrow().invocationId();
        runtime.run(request, AgentContext.of("main-agent"));
        AgentInvocation second = runtime.latestInvocation("same-session").orElseThrow();

        assertThat(contexts).extracting(AgentContext::invocationId)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
        assertThat(second.invocationId()).isNotEqualTo(firstId);
        assertThat(second.sessionId()).isEqualTo("same-session");
        assertThat(second.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(second.finishedAt()).isNotNull();
    }
}
