package com.github.agentos.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigAgentLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper(
            YAMLFactory.builder().build());

    @TempDir Path tempDir;

    @Test
    void loadsSpecialistAndSequentialAgents() throws Exception {
        String yaml = """
                agents:
                  - id: research-agent
                    kind: specialist
                    description: "Research a topic"
                    instruction: "You are a research expert"
                    tools: [web_search, web_fetch]
                    save-output: false
                  - id: pipeline
                    kind: sequential
                    description: "Search then report"
                    sub-agents: [research-agent]
                """;
        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> defs = loader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThat(defs).hasSize(2);
        assertThat(defs.get(0).id()).isEqualTo("research-agent");
        assertThat(defs.get(0).isSpecialist()).isTrue();
        assertThat(defs.get(0).tools()).containsExactly("web_search", "web_fetch");
        assertThat(defs.get(1).id()).isEqualTo("pipeline");
        assertThat(defs.get(1).isSequential()).isTrue();
        assertThat(defs.get(1).subAgents()).containsExactly("research-agent");
    }

    @Test
    void returnsEmptyListWhenNoAgentsKey() throws Exception {
        String yaml = "other: value";
        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> defs = loader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(defs).isEmpty();
    }

    @Test
    void loadsFromFile() throws Exception {
        String yaml = """
                agents:
                  - id: test-agent
                    kind: specialist
                    description: "Test"
                    instruction: "You are a test agent"
                """;
        Path file = tempDir.resolve("agents.yaml");
        Files.writeString(file, yaml);
        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> defs = loader.load(file);
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).id()).isEqualTo("test-agent");
    }

    @Test
    void exposeAsToolDefaultsToTrue() throws Exception {
        String yaml = """
                agents:
                  - id: a
                    kind: specialist
                    instruction: "You are a test agent"
                """;
        ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
        List<AgentDefinition> defs = loader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(defs.get(0).exposeAsTool()).isTrue();
    }
}
