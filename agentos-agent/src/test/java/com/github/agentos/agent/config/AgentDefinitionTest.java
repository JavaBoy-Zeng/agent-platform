package com.github.agentos.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDefinitionTest {

    @Test
    void specialistDefinition_normalizesFields() {
        AgentDefinition def = new AgentDefinition(
                "research-agent", "Specialist", "desc", "instruction",
                List.of("web_search"), false, List.of(), true);
        assertThat(def.id()).isEqualTo("research-agent");
        assertThat(def.kind()).isEqualTo("specialist");
        assertThat(def.isSpecialist()).isTrue();
        assertThat(def.isSequential()).isFalse();
        assertThat(def.tools()).containsExactly("web_search");
    }

    @Test
    void sequentialDefinition_requiresSubAgents() {
        AgentDefinition def = new AgentDefinition(
                "pipeline", "sequential", "desc", "",
                List.of(), false, List.of("a", "b"), true);
        assertThat(def.isSequential()).isTrue();
        assertThat(def.subAgents()).containsExactly("a", "b");
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new AgentDefinition(
                " ", "specialist", "d", "instruction", null, false, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedKind() {
        assertThatThrownBy(() -> new AgentDefinition(
                "a", "unknown", "d", "instruction", null, false, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported kind");
    }

    @Test
    void specialistRejectsBlankInstruction() {
        assertThatThrownBy(() -> new AgentDefinition(
                "a", "specialist", "d", "", null, false, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instruction must not be blank");
    }

    @Test
    void sequentialRejectsEmptySubAgents() {
        assertThatThrownBy(() -> new AgentDefinition(
                "a", "sequential", "d", "", null, false, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAgents must not be empty");
    }

    @Test
    void defaults_nullCollectionsBecomeEmpty() {
        AgentDefinition def = new AgentDefinition(
                "a", "specialist", null, "instruction", null, false, null, true);
        assertThat(def.description()).isEmpty();
        assertThat(def.tools()).isEmpty();
        assertThat(def.subAgents()).isEmpty();
    }

    @Test
    void kindIsCaseInsensitive() {
        AgentDefinition def = new AgentDefinition(
                "a", "SPECIALIST", "d", "instruction", null, false, null, true);
        assertThat(def.kind()).isEqualTo("specialist");
    }
}
