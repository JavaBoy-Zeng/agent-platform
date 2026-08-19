package com.github.agentos.agent.config;

import java.util.List;
import java.util.Objects;

/**
 * YAML 配置文件中单个 Agent 的定义。
 *
 * <p>与 {@link AgentFactory} 配合使用，从 YAML 声明构建 Agent 实例。
 * 支持两种 Agent 形态：</p>
 * <ul>
 *   <li>{@code specialist}：由 LLM 驱动、可调用指定工具的单步专家 Agent</li>
 *   <li>{@code sequential}：按序执行多个子 Agent 的串行编排 Agent</li>
 * </ul>
 *
 * @param id          Agent 唯一标识
 * @param kind        Agent 类别（{@code specialist} 或 {@code sequential}）
 * @param description 能力说明（供路由与工具暴露使用）
 * @param instruction 系统指令（specialist 类别必填）
 * @param tools       工具名列表（specialist 类别可选；sequential 忽略）
 * @param saveOutput  是否将输出写入文件（specialist 类别可选，默认 false）
 * @param subAgents   子 Agent 标识列表（sequential 类别必填；specialist 忽略）
 * @param exposeAsTool 是否暴露为工具供规划器调用（默认 true）
 */
public record AgentDefinition(
        String id,
        String kind,
        String description,
        String instruction,
        List<String> tools,
        boolean saveOutput,
        List<String> subAgents,
        boolean exposeAsTool) {

    /** 创建并校验 Agent 定义。 */
    public AgentDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        kind = kind.trim().toLowerCase(java.util.Locale.ROOT);
        description = description == null ? "" : description;
        instruction = instruction == null ? "" : instruction;
        tools = tools == null ? List.of() : List.copyOf(tools);
        subAgents = subAgents == null ? List.of() : List.copyOf(subAgents);
        validate(id, kind, instruction, subAgents);
    }

    private static void validate(String id, String kind, String instruction, List<String> subAgents) {
        if (!"specialist".equals(kind) && !"sequential".equals(kind)) {
            throw new IllegalArgumentException(
                    "unsupported kind: " + kind + " (expected: specialist or sequential)");
        }
        if ("specialist".equals(kind) && instruction.isBlank()) {
            throw new IllegalArgumentException(
                    "instruction must not be blank for kind=specialist (id=" + id + ")");
        }
        if ("sequential".equals(kind) && subAgents.isEmpty()) {
            throw new IllegalArgumentException(
                    "subAgents must not be empty for kind=sequential (id=" + id + ")");
        }
    }

    /** 返回是否为 specialist 类别。 */
    public boolean isSpecialist() {
        return "specialist".equals(kind);
    }

    /** 返回是否为 sequential 类别。 */
    public boolean isSequential() {
        return "sequential".equals(kind);
    }
}
