package com.github.agentos.tool.skill;

import java.util.Map;
import java.util.Objects;

/**
 * 一项可被 Agent 按需加载的技能定义。
 *
 * <p>工具是“能力函数”，技能是“教 Agent 怎么完成一类任务”的操作指令。
 * 技能正文描述执行步骤、约束、最佳实践与输出格式，由 Agent 在需要时
 * 经 {@code load_skill} 工具加载进上下文，而不是常驻提示词。</p>
 *
 * @param id 技能唯一标识（通常是 frontmatter 中的 name）
 * @param name 技能展示名称
 * @param description 面向模型的一句能力描述，用于决定何时加载
 * @param instructions 技能正文指令（Markdown）
 * @param metadata frontmatter 中的其余扩展字段
 * @param source 技能来源位置（文件路径或资源路径）
 */
public record AgentSkill(
        String id,
        String name,
        String description,
        String instructions,
        Map<String, Object> metadata,
        String source) {

    /** 校验必填字段并复制元数据快照。 */
    public AgentSkill {
        id = requireText(id, "id");
        name = requireText(name, "name");
        description = requireText(description, "description");
        instructions = requireText(instructions, "instructions");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        source = source == null ? "" : source;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("skill " + field + " must not be blank");
        }
        return value.strip();
    }

    /** 校验 name/description/instructions 非空。 */
    public static void requireValid(String id, String name, String description, String instructions) {
        Objects.requireNonNull(id, "id must not be null");
        requireText(name, "name");
        requireText(description, "description");
        requireText(instructions, "instructions");
    }
}
