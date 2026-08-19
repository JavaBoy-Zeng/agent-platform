package com.github.agentos.tool.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClasspathSkillSourceTest {

    @Test
    void loadsDeclaredResourcesFromClasspath() throws Exception {
        ClasspathSkillSource source = new ClasspathSkillSource(
                List.of("skills/test-greeting/SKILL.md"));

        List<AgentSkill> skills = source.load();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).id()).isEqualTo("test-greeting");
        assertThat(skills.get(0).description()).isEqualTo("测试用问候技能");
        assertThat(skills.get(0).instructions()).contains("问候语");
        assertThat(skills.get(0).source()).isEqualTo("skills/test-greeting/SKILL.md");
    }

    @Test
    void missingResourceFails() {
        ClasspathSkillSource source = new ClasspathSkillSource(
                List.of("skills/not-exist/SKILL.md"));

        assertThatThrownBy(source::load)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("not-exist");
    }

    @Test
    void emptyResourceListYieldsEmptySkills() throws Exception {
        assertThat(new ClasspathSkillSource(List.of()).load()).isEmpty();
    }
}
