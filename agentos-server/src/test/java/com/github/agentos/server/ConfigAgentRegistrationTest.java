package com.github.agentos.server;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.config.AgentBuildContext;
import com.github.agentos.agent.config.AgentDefinition;
import com.github.agentos.agent.config.AgentFactory;
import com.github.agentos.agent.config.ConfigAgentLoader;
import com.github.agentos.agent.config.ConfigDrivenAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.registry.InMemoryAgentRegistry;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 YAML 配置驱动的 Agent 能被加载、构建并注册到 AgentRegistry 和 ToolRegistry。
 */
class ConfigAgentRegistrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper(
            YAMLFactory.builder().build());

    @TempDir Path tempDir;

    @Test
    void loadsAndRegistersAgents() throws Exception {
        String yaml = """
                agents:
                  - id: research-agent
                    kind: specialist
                    description: "Research a topic"
                    instruction: "You are a research expert"
                    tools: []
                    expose-as-tool: true
                  - id: summary-agent
                    kind: specialist
                    description: "Summarize content"
                    instruction: "You are a summary expert"
                    expose-as-tool: false
                """;
        Path file = tempDir.resolve("agents.yaml");
        Files.writeString(file, yaml);

        // Load definitions
        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> definitions = loader.load(file);
        assertThat(definitions).hasSize(2);

        // Build agents
        ChatClient chatClient = (sessionId, request) -> "LLM answer";
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        AgentBuildContext context = new AgentBuildContext(chatClient, toolRegistry);
        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(definitions);
        assertThat(agents).hasSize(2);

        // Register to AgentRegistry
        AgentRegistry agentRegistry = new InMemoryAgentRegistry();
        for (Agent agent : agents) {
            agentRegistry.register(agent);
        }

        // Verify registration
        assertThat(agentRegistry.find("research-agent")).isPresent();
        assertThat(agentRegistry.find("summary-agent")).isPresent();

        // Verify tool exposure
        Agent researchAgent = agents.get(0);
        assertThat(researchAgent).isInstanceOf(ConfigDrivenAgent.class);
        ConfigDrivenAgent driven = (ConfigDrivenAgent) researchAgent;
        assertThat(driven.definition().exposeAsTool()).isTrue();

        AgentToolAdapter adapter = new AgentToolAdapter(driven);
        assertThat(adapter.name()).isEqualTo("research-agent");

        // summary-agent should not be exposed as tool
        Agent summaryAgent = agents.get(1);
        ConfigDrivenAgent summaryDriven = (ConfigDrivenAgent) summaryAgent;
        assertThat(summaryDriven.definition().exposeAsTool()).isFalse();
    }

    @Test
    void loadsSequentialAgent() throws Exception {
        String yaml = """
                agents:
                  - id: step1
                    kind: specialist
                    description: "Step 1"
                    instruction: "You are step 1"
                  - id: step2
                    kind: specialist
                    description: "Step 2"
                    instruction: "You are step 2"
                  - id: pipeline
                    kind: sequential
                    description: "Run steps in order"
                    sub-agents: [step1, step2]
                """;
        Path file = tempDir.resolve("pipeline.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> definitions = loader.load(file);
        assertThat(definitions).hasSize(3);

        ChatClient chatClient = (sessionId, request) -> "done";
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        AgentBuildContext context = new AgentBuildContext(chatClient, toolRegistry);
        AgentFactory factory = new AgentFactory(context);
        List<Agent> agents = factory.buildAll(definitions);
        assertThat(agents).hasSize(3);

        // Pipeline should be the last agent
        Agent pipeline = agents.get(2);
        assertThat(pipeline.id()).isEqualTo("pipeline");

        AgentRegistry registry = new InMemoryAgentRegistry();
        agents.forEach(registry::register);
        assertThat(registry.find("pipeline")).isPresent();
    }
}
