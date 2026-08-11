package com.github.agentos.planner;

import com.github.agentos.tool.ToolCall;

import java.util.Objects;

/**
 * 一条不可变计划步骤。
 *
 * @param id 步骤标识
 * @param description 步骤用途说明
 * @param optional 失败后是否允许 FailureClassifier 跳过
 * @param toolCall 工具调用
 */
public record PlanStep(
        String id,
        String description,
        boolean optional,
        ToolCall toolCall) {

    /** 创建并校验计划步骤。 */
    public PlanStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("step id must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("step description must not be blank");
        }
        toolCall = Objects.requireNonNull(toolCall, "toolCall must not be null");
    }
}
