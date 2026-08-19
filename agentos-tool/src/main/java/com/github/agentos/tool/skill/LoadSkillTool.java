package com.github.agentos.tool.skill;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把注册表中的技能按需加载进对话上下文的工具。
 *
 * <p>技能正文不常驻提示词：规划器先从工具描述看到全部可用技能的
 * 名称与能力摘要，需要时以 {@code skill_id} 调用本工具，获得完整的
 * 操作步骤指令后再继续规划执行，从而降低固定 token 消耗。</p>
 */
public final class LoadSkillTool implements AgentTool {

    private final SkillRegistry registry;

    /**
     * 创建技能加载工具。
     *
     * @param registry 技能注册表
     */
    public LoadSkillTool(SkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String name() {
        return "load_skill";
    }

    @Override
    public String description() {
        String listing = registry.all().isEmpty()
                ? "(当前没有可用技能)"
                : registry.all().stream()
                        .map(skill -> skill.id() + " - " + skill.description())
                        .collect(Collectors.joining("; "));
        return "加载一项技能的详细操作指令。技能描述了完成某类任务的步骤、约束与输出格式，"
                + "在处理相关任务时应先加载对应技能再执行。可用技能: " + listing;
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(new ToolParameter(
                "skill_id",
                ToolParameter.ValueType.STRING,
                "要加载的技能标识（见工具说明中的可用技能列表）",
                true));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        Object id = call.arguments().get("skill_id");
        if (!(id instanceof String text) || text.isBlank()) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT, "missing required argument: skill_id");
        }
        return registry.find(text.strip())
                .map(this::render)
                .orElseGet(() -> ToolResult.failure(
                        ToolFailureType.INVALID_ARGUMENT,
                        "unknown skill: " + text.strip()
                                + ". 可用技能: " + registry.all().stream()
                                        .map(AgentSkill::id).collect(Collectors.joining(", "))));
    }

    private ToolResult render(AgentSkill skill) {
        StringBuilder output = new StringBuilder()
                .append("# 技能: ").append(skill.name()).append('\n')
                .append("说明: ").append(skill.description()).append('\n');
        if (!skill.metadata().isEmpty()) {
            output.append("元数据: ").append(skill.metadata()).append('\n');
        }
        if (!skill.source().isBlank()) {
            output.append("来源: ").append(skill.source()).append('\n');
        }
        output.append("\n## 操作指南\n\n").append(skill.instructions());
        return ToolResult.success(output.toString());
    }
}
