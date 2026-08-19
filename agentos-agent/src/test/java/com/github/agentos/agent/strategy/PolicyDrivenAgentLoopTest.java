package com.github.agentos.agent.strategy;

import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 执行策略路由测试。 */
class PolicyDrivenAgentLoopTest {

    @Test
    void routesRequestToSelectedStrategy() {
        AgentLoop direct = strategy("direct");
        AgentLoop react = strategy("react");
        AgentLoop plan = strategy("plan");
        PolicyDrivenAgentLoop router = new PolicyDrivenAgentLoop(
                (request, context) -> ExecutionMode.valueOf(
                        String.valueOf(request.attributes().get("mode"))),
                direct, react, plan);

        AgentState result = router.run(
                new AgentRequest("s1", "run", Map.of("mode", "REACT")),
                InvocationContext.of("main-agent"), AgentState.ready().startNextIteration());

        assertThat(result.output()).isEqualTo("react");
    }

    private static AgentLoop strategy(String output) {
        return (request, context, running) -> running.complete(output);
    }
}
