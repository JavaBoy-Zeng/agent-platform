package com.github.agentos.tool.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSkillSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDirectorySkillsAndSingleFileSkills() throws IOException {
        writeSkillDir("java-flamegraph", "Java 火焰图分析", "如何分析 Java CPU 火焰图");
        writeFile(tempDir.resolve("code-review.md"), """
                ---
                name: code-review
                description: 代码评审流程
                ---
                1. 先看结构
                2. 再看命名
                """);

        List<AgentSkill> skills = new LocalSkillSource(tempDir).load();

        assertThat(skills).extracting(AgentSkill::id)
                .containsExactlyInAnyOrder("java-flamegraph", "code-review");
        AgentSkill flamegraph = skills.stream()
                .filter(skill -> skill.id().equals("java-flamegraph")).findFirst().orElseThrow();
        assertThat(flamegraph.name()).isEqualTo("java-flamegraph");
        assertThat(flamegraph.description()).isEqualTo("Java 火焰图分析");
        assertThat(flamegraph.instructions()).contains("async-profiler");
        assertThat(flamegraph.source()).contains("SKILL.md");
    }

    @Test
    void frontmatterMetadataLandsInMetadataMap() throws IOException {
        Path file = tempDir.resolve("rich.md");
        writeFile(file, """
                ---
                name: rich
                description: 带扩展字段
                author: agentos
                version: 2
                ---
                正文指令
                """);

        List<AgentSkill> skills = new LocalSkillSource(tempDir).load();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).metadata())
                .containsEntry("author", "agentos")
                .containsEntry("version", 2);
        assertThat(skills.get(0).metadata()).doesNotContainKey("name");
    }

    @Test
    void missingRootDirectoryYieldsEmptyList() throws IOException {
        assertThat(new LocalSkillSource(tempDir.resolve("not-exists")).load()).isEmpty();
    }

    @Test
    void missingFrontmatterFails() throws IOException {
        writeFile(tempDir.resolve("broken.md"), "没有 frontmatter 的内容");

        LocalSkillSource source = new LocalSkillSource(tempDir);
        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void blankInstructionsFail() throws IOException {
        writeFile(tempDir.resolve("empty-body.md"), """
                ---
                name: empty-body
                description: 只有元数据
                ---
                """);

        LocalSkillSource source = new LocalSkillSource(tempDir);
        assertThatThrownBy(source::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instructions");
    }

    private void writeSkillDir(String id, String description, String title) throws IOException {
        Path dir = tempDir.resolve(id);
        Files.createDirectories(dir);
        writeFile(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                # %s

                使用 async-profiler 采集后按热点排序。
                """.formatted(id, description, title));
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.writeString(file, content);
    }
}
