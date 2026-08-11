package com.github.agentos.planner;

import com.github.agentos.tool.ToolFailureType;

import java.util.Objects;

/**
 * 工具步骤完成后提供给 Planner 的有界观察结果。
 *
 * <p>Observation 不携带完整工具输出，只保留决策所需摘要，避免历史结果在多轮规划中
 * 反复放大模型提示词。</p>
 *
 * @param planId 所属计划标识
 * @param stepId 步骤标识
 * @param toolName 工具名称
 * @param status 步骤终态
 * @param summary 成功输出或失败原因的有界摘要
 * @param failureType 结构化失败类型
 * @param attempts 实际工具调用次数
 */
public record Observation(
        String planId,
        String stepId,
        String toolName,
        StepStatus status,
        String summary,
        ToolFailureType failureType,
        int attempts) {

    /** 创建不可变观察结果。 */
    public Observation {
        planId = requireText(planId, "planId");
        stepId = requireText(stepId, "stepId");
        toolName = requireText(toolName, "toolName");
        status = Objects.requireNonNull(status, "status must not be null");
        summary = summary == null ? "" : summary;
        failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
