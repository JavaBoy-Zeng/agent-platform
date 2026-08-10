package com.github.agentos.server;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "agentos.memory.mode=memory")
@ActiveProfiles("demo")
class AgentRuntimeIntegrationTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private MemoryService memoryService;

    @Test
    void capturesOnlyTheCompletedTurnAndBuildsL1ToL3() {
        AgentContext context = AgentContext.of("main-agent", "session-1", "I prefer Java");

        AgentState result = runtime.run(context);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("I prefer Java");
        assertThat(memoryService.awaitIdle(Duration.ofSeconds(2))).isTrue();

        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");
        assertThat(memoryService.recentTurns(scope, 10)).singleElement().satisfies(turn -> {
            assertThat(turn.userInput()).isEqualTo("I prefer Java");
            assertThat(turn.assistantOutput()).isEqualTo("I prefer Java");
            assertThat(turn.toolOutputs()).containsExactly("I prefer Java");
        });
        assertThat(memoryService.atomicMemories(scope)).isNotEmpty();
        assertThat(memoryService.scenarios(scope)).isNotEmpty();
        assertThat(memoryService.profile(scope)).isNotNull();
    }
}
