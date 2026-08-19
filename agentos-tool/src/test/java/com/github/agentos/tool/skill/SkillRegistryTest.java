package com.github.agentos.tool.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {

    private static AgentSkill skill(String id) {
        return new AgentSkill(id, id, id + " 的描述", "指令正文", java.util.Map.of(), "");
    }

    @Test
    void registerRejectsDuplicateId() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("a"));

        assertThatThrownBy(() -> registry.register(skill("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void registerIfAbsentKeepsFirstWinsSemantics() {
        SkillRegistry registry = new SkillRegistry();
        assertThat(registry.registerIfAbsent(skill("a"))).isTrue();
        assertThat(registry.registerIfAbsent(skill("a"))).isFalse();

        assertThat(registry.find("a")).hasValueSatisfying(existing ->
                assertThat(existing.description()).isEqualTo("a 的描述"));
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void loadFromAggregatesSourcesWithFirstWinsPriority() throws Exception {
        SkillRegistry registry = new SkillRegistry();
        SkillSource primary = () -> List.of(skill("shared"), skill("local-only"));
        SkillSource fallback = () -> List.of(skill("shared"), skill("classpath-only"));

        registry.loadFrom(List.of(primary, fallback));

        assertThat(registry.all()).extracting(AgentSkill::id)
                .containsExactlyInAnyOrder("shared", "local-only", "classpath-only");
    }

    @Test
    void findUnknownReturnsEmpty() {
        assertThat(new SkillRegistry().find("missing")).isEmpty();
    }

    @Test
    void agentSkillValidatesRequiredFields() {
        assertThatThrownBy(() -> new AgentSkill("", "n", "d", "i", java.util.Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentSkill("id", "n", " ", "i", java.util.Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
