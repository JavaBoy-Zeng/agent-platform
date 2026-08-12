package com.github.agentos.agent;

import com.github.agentos.kernel.AgentExecutionContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AgentRegistry 行为测试。 */
class InMemoryAgentRegistryTest {

    @Test
    void registersFindsAndRejectsDuplicateAgentIds() {
        Agent main = agent("main-agent");
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of(main));

        assertThat(registry.find("main-agent")).containsSame(main);
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.register(agent("main-agent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    private static Agent agent(String id) {
        return new Agent() {
            @Override public String id() { return id; }
            @Override public String description() { return "test"; }
            @Override public AgentExecutionResult run(
                    AgentRequest request, AgentExecutionContext context) {
                return AgentExecutionResult.from(
                        AgentState.ready().startNextIteration().complete("ok"), null);
            }
        };
    }
}
