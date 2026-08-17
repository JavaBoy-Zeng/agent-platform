package com.github.agentos.tool.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 提供给任务规划器和模型客户端的只读工具定义。
 *
 * @param name 工具唯一名称
 * @param description 工具能力说明
 * @param riskLevel 工具风险等级
 * @param parameters 工具参数结构
 */
public record ToolDefinition(
        String name,
        String description,
        AgentTool.RiskLevel riskLevel,
        List<ToolParameter> parameters) {

    /**
     * 创建并校验工具定义。
     *
     * @throws IllegalArgumentException 当名称、说明为空或参数名称重复时抛出
     * @throws NullPointerException 当风险等级或参数列表为 {@code null} 时抛出
     */
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));

        Set<String> parameterNames = new HashSet<>();
        for (ToolParameter parameter : parameters) {
            if (!parameterNames.add(parameter.name())) {
                throw new IllegalArgumentException("duplicate tool parameter: " + parameter.name());
            }
        }
    }

    /**
     * 从工具实现创建供规划使用的定义快照。
     *
     * @param tool 工具实现
     * @return 工具定义快照
     */
    public static ToolDefinition from(AgentTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        return new ToolDefinition(
                tool.name(),
                tool.description(),
                tool.riskLevel(),
                tool.parameters());
    }
}
