package com.github.agentos.agent.registry;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.kernel.InvocationContext;
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
        Agent main = agent("plan-execute-agent");
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(List.of(main));

        assertThat(registry.find("plan-execute-agent")).containsSame(main);
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.register(agent("plan-execute-agent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void listsAllRegisteredAgentsSortedById() {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry(
                List.of(agent("plan-execute-agent"), agent("code-agent"), agent("search-agent")));

        assertThat(registry.all()).extracting(Agent::id)
                .containsExactly("code-agent", "plan-execute-agent", "search-agent");
    }

    @Test
    void listsEmptyWhenNothingRegistered() {
        assertThat(new InMemoryAgentRegistry().all()).isEmpty();
    }

    private static Agent agent(String id) {
        return new Agent() {
            @Override public String id() { return id; }
            @Override public String description() { return "test"; }
            @Override public AgentExecutionResult run(
                    AgentRequest request, InvocationContext context) {
                return AgentExecutionResult.from(
                        AgentState.ready().startNextIteration().complete("ok"), null);
            }
        };
    }
}
