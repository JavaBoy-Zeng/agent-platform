package com.github.agentos.agent.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentFactoryTest {

    private final ChatClient echoClient = (sessionId, request) -> "answer from LLM";

    private final AgentTool echoTool = new AgentTool() {
        @Override
        public String name() { return "echo"; }
        @Override
        public String description() { return "echo tool"; }
        @Override
        public ToolResult execute(ToolContext context, ToolCall call) {
            return ToolResult.success("echo: " + call.arguments().get("message"));
        }
    };

    private final ToolRegistry registry = new ToolRegistry(List.of(echoTool));

    private final AgentBuildContext context = new AgentBuildContext(echoClient, registry);

    @Test
    void buildSpecialist_agentHasIdAndDescription() {
        AgentDefinition def = new AgentDefinition(
                "test-agent", "specialist", "test description", "You are a test agent",
                List.of("echo"), false, List.of(), true);
        AgentFactory factory = new AgentFactory(context);

        List<Agent> agents = factory.buildAll(List.of(def));

        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).id()).isEqualTo("test-agent");
        assertThat(agents.get(0).description()).isEqualTo("test description");
    }

    @Test
    void buildSpecialist_missingToolIsSkipped() {
        AgentDefinition def = new AgentDefinition(
                "test-agent", "specialist", "desc", "instruction",
                List.of("nonexistent_tool"), false, List.of(), true);
        AgentFactory factory = new AgentFactory(context);

        List<Agent> agents = factory.buildAll(List.of(def));
        assertThat(agents).hasSize(1);
        // Agent builds successfully even with missing tool (graceful degradation)
    }

    @Test
    void buildSequential_referencesBuiltSpecialists() {
        AgentDefinition specialist1 = new AgentDefinition(
                "search", "specialist", "search agent", "search instruction",
                List.of("echo"), false, List.of(), true);
        AgentDefinition specialist2 = new AgentDefinition(
                "report", "specialist", "report agent", "report instruction",
                List.of(), false, List.of(), true);
        AgentDefinition sequential = new AgentDefinition(
                "pipeline", "sequential", "search then report", "",
                List.of(), false, List.of("search", "report"), true);

        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(List.of(specialist1, specialist2, sequential));

        assertThat(agents).hasSize(3);
        Agent seqAgent = agents.get(2);
        assertThat(seqAgent.id()).isEqualTo("pipeline");

        // Running the sequential agent should execute both specialists
        AgentRequest request = AgentRequest.of("s1", "test objective");
        var result = seqAgent.run(request, InvocationContext.of("pipeline"));
        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
    }

    @Test
    void buildSequential_missingSubAgentThrows() {
        AgentDefinition seq = new AgentDefinition(
                "pipeline", "sequential", "desc", "",
                List.of(), false, List.of("nonexistent"), true);
        AgentFactory factory = new AgentFactory(context);

        assertThatThrownBy(() -> factory.buildAll(List.of(seq)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subAgent not found");
    }

    @Test
    void buildSpecialist_runReturnsCompletedResult() {
        AgentDefinition def = new AgentDefinition(
                "simple", "specialist", "simple agent", "You are simple",
                List.of(), false, List.of(), true);
        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(List.of(def));

        AgentRequest request = AgentRequest.of("s1", "hello");
        var result = agents.get(0).run(request, InvocationContext.of("simple"));
        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("answer from LLM");
    }

    @Test
    void buildSpecialist_runFailsWhenChatClientErrors() {
        ChatClient errorClient = (sessionId, request) -> {
            throw new RuntimeException("model unavailable");
        };
        AgentBuildContext errorContext = new AgentBuildContext(errorClient, registry);
        AgentDefinition def = new AgentDefinition(
                "fail-agent", "specialist", "desc", "instruction",
                List.of(), false, List.of(), true);
        AgentFactory factory = new AgentFactory(errorContext);

        List<Agent> agents = factory.buildAll(List.of(def));
        AgentRequest request = AgentRequest.of("s1", "test");
        var result = agents.get(0).run(request, InvocationContext.of("fail-agent"));
        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("model unavailable");
    }

    @Test
    void buildParallel_referencesBuiltSpecialists() {
        AgentDefinition specialist1 = new AgentDefinition(
                "search-a", "specialist", "search agent a", "search instruction",
                List.of("echo"), false, List.of(), true);
        AgentDefinition specialist2 = new AgentDefinition(
                "search-b", "specialist", "search agent b", "search instruction",
                List.of("echo"), false, List.of(), true);
        AgentDefinition parallel = new AgentDefinition(
                "parallel-search", "parallel", "parallel search", "",
                List.of(), false, List.of("search-a", "search-b"), true);

        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(List.of(specialist1, specialist2, parallel));

        assertThat(agents).hasSize(3);
        Agent parallelAgent = agents.get(2);
        assertThat(parallelAgent.id()).isEqualTo("parallel-search");

        // 并行执行两个子 Agent，状态应为 COMPLETED。
        AgentRequest request = AgentRequest.of("s1", "test parallel");
        var result = parallelAgent.run(request, InvocationContext.of("parallel-search"));
        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
    }

    @Test
    void buildLoop_referencesBuiltSpecialists() {
        AgentDefinition specialist = new AgentDefinition(
                "worker", "specialist", "worker agent", "worker instruction",
                List.of(), false, List.of(), true);
        AgentDefinition loop = new AgentDefinition(
                "loop-pipeline", "loop", "loop worker", "",
                List.of(), false, List.of("worker"), true);

        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(List.of(specialist, loop));

        assertThat(agents).hasSize(2);
        Agent loopAgent = agents.get(1);
        assertThat(loopAgent.id()).isEqualTo("loop-pipeline");

        AgentRequest request = AgentRequest.of("s1", "test loop");
        var result = loopAgent.run(request, InvocationContext.of("loop-pipeline"));
        // loop 会执行 worker 直到终态或 maxIterations（默认 3）；COMPLETED 即可。
        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
    }

    @Test
    void buildParallel_missingSubAgentThrows() {
        AgentDefinition parallel = new AgentDefinition(
                "parallel-bad", "parallel", "desc", "",
                List.of(), false, List.of("nonexistent"), true);
        AgentFactory factory = new AgentFactory(context);

        assertThatThrownBy(() -> factory.buildAll(List.of(parallel)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subAgent not found");
    }

    @Test
    void unsupportedKindThrows() {
        // AgentDefinition 的构造器校验 kind，应在构造期就抛 IllegalArgumentException。
        assertThatThrownBy(() -> new AgentDefinition(
                "bad", "unknown_kind", "desc", "instruction",
                List.of(), false, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported kind");
    }

    @Test
    void buildMultipleAgents_allRegisteredCorrectly() {
        AgentDefinition def1 = new AgentDefinition(
                "a1", "specialist", "d1", "instruction1", List.of(), false, List.of(), true);
        AgentDefinition def2 = new AgentDefinition(
                "a2", "specialist", "d2", "instruction2", List.of(), false, List.of(), true);

        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(List.of(def1, def2));

        assertThat(agents).hasSize(2);
        assertThat(agents.get(0).id()).isEqualTo("a1");
        assertThat(agents.get(1).id()).isEqualTo("a2");
    }
}
