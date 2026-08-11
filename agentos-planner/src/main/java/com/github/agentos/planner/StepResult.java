package com.github.agentos.planner;

import com.github.agentos.tool.ToolFailureType;

import java.util.Objects;

/**
 * 一条已经结束的计划步骤结果。
 *
 * @param planId 所属计划标识
 * @param stepId 步骤标识
 * @param toolName 工具名称
 * @param status 终态
 * @param output 成功输出
 * @param error 失败、跳过或拒绝原因
 * @param failureType 结构化失败类型
 * @param attempts 实际工具调用次数
 */
public record StepResult(
        String planId,
        String stepId,
        String toolName,
        StepStatus status,
        String output,
        String error,
        ToolFailureType failureType,
        int attempts) {

    /** 创建并规范化步骤结果。 */
    public StepResult {
        planId = requireText(planId, "planId");
        stepId = requireText(stepId, "stepId");
        toolName = requireText(toolName, "toolName");
        status = Objects.requireNonNull(status, "status must not be null");
        output = output == null ? "" : output;
        error = error == null ? "" : error;
        failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if (status == StepStatus.COMPLETED && failureType != ToolFailureType.NONE) {
            throw new IllegalArgumentException("completed result must have failureType NONE");
        }
        if (status != StepStatus.COMPLETED && failureType == ToolFailureType.NONE) {
            throw new IllegalArgumentException("non-completed result must describe a failure");
        }
    }

    public static StepResult completed(AgentPlan plan, PlanStep step, String output, int attempts) {
        return new StepResult(
                plan.id(), step.id(), step.toolCall().toolName(), StepStatus.COMPLETED,
                output, "", ToolFailureType.NONE, attempts);
    }

    public static StepResult failed(
            AgentPlan plan, PlanStep step, String error, ToolFailureType failureType, int attempts) {
        return new StepResult(
                plan.id(), step.id(), step.toolCall().toolName(), StepStatus.FAILED,
                "", error, failureType, attempts);
    }

    public static StepResult skipped(
            AgentPlan plan, PlanStep step, String error, ToolFailureType failureType, int attempts) {
        return new StepResult(
                plan.id(), step.id(), step.toolCall().toolName(), StepStatus.SKIPPED,
                "", error, failureType, attempts);
    }

    public static StepResult rejected(AgentPlan plan, PlanStep step, String error) {
        return new StepResult(
                plan.id(), step.id(), step.toolCall().toolName(), StepStatus.REJECTED,
                "", error, ToolFailureType.SECURITY_DENIED, 0);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
