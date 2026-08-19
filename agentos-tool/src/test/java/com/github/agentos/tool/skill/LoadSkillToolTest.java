package com.github.agentos.tool.skill;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoadSkillToolTest {

    private SkillRegistry registryWithSkills() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new AgentSkill(
                "java-flamegraph", "java-flamegraph",
                "Java CPU 火焰图分析技能",
                "1. 采集火焰图\n2. 排序热点",
                Map.of("difficulty", "advanced"), "skills/java-flamegraph/SKILL.md"));
        return registry;
    }

    @Test
    void descriptionListsAvailableSkills() {
        LoadSkillTool tool = new LoadSkillTool(registryWithSkills());

        assertThat(tool.name()).isEqualTo("load_skill");
        assertThat(tool.description())
                .contains("java-flamegraph")
                .contains("Java CPU 火焰图分析技能");
    }

    @Test
    void executeReturnsFormattedInstructions() {
        LoadSkillTool tool = new LoadSkillTool(registryWithSkills());

        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("load_skill", Map.of("skill_id", "java-flamegraph")));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("# 技能: java-flamegraph")
                .contains("采集火焰图")
                .contains("difficulty=advanced");
    }

    @Test
    void unknownSkillIdFailsWithAvailableListing() {
        LoadSkillTool tool = new LoadSkillTool(registryWithSkills());

        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("load_skill", Map.of("skill_id", "nope")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unknown skill: nope").contains("java-flamegraph");
    }

    @Test
    void missingSkillIdArgumentFails() {
        LoadSkillTool tool = new LoadSkillTool(registryWithSkills());

        ToolResult result = tool.execute(
                ToolContexts.testContext(tool),
                new ToolCall("load_skill", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("skill_id");
    }

    @Test
    void emptyRegistryDescriptionHasPlaceholder() {
        LoadSkillTool tool = new LoadSkillTool(new SkillRegistry());

        assertThat(tool.description()).contains("没有可用技能");
    }
}
