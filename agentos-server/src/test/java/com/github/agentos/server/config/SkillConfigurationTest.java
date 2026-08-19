package com.github.agentos.server.config;

import com.github.agentos.tool.skill.AgentSkill;
import com.github.agentos.tool.skill.LoadSkillTool;
import com.github.agentos.tool.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能体系装配测试。 */
class SkillConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(SkillConfiguration.class));

    @Test
    void loadsBuiltinClasspathSkillsAndExposesTool() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SkillRegistry.class);
            assertThat(context).hasSingleBean(LoadSkillTool.class);

            SkillRegistry registry = context.getBean(SkillRegistry.class);
            assertThat(registry.all()).extracting(AgentSkill::id)
                    .containsExactlyInAnyOrder("report-writing", "code-review");
            assertThat(context.getBean(LoadSkillTool.class).name()).isEqualTo("load_skill");
            assertThat(context.getBean(LoadSkillTool.class).description())
                    .contains("report-writing");
        });
    }

    @Test
    void localDirectoryOverridesClasspathSkillWithSameId() throws IOException {
        Path skillDir = tempDir.resolve("report-writing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: report-writing
                description: 本地覆盖版报告技能
                ---

                本地目录优先于 classpath，同 ID 技能以本地版本为准。
                """);

        runner.withPropertyValues("agentos.skills.root=" + tempDir)
                .run(context -> {
                    SkillRegistry registry = context.getBean(SkillRegistry.class);
                    assertThat(registry.find("report-writing"))
                            .hasValueSatisfying(skill ->
                                    assertThat(skill.description()).isEqualTo("本地覆盖版报告技能"));
                });
    }

    @Test
    void missingLocalRoot_stillLoadsClasspathSkills() {
        runner.withPropertyValues("agentos.skills.root=" + tempDir.resolve("no-such-dir"))
                .run(context -> {
                    SkillRegistry registry = context.getBean(SkillRegistry.class);
                    assertThat(registry.all()).hasSize(2);
                });
    }

    @Test
    void disabled_skipsAllBeans() {
        runner.withPropertyValues("agentos.skills.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SkillRegistry.class);
                    assertThat(context).doesNotHaveBean(LoadSkillTool.class);
                });
    }
}
