package com.github.agentos.planner;

import com.github.agentos.tool.ToolResult;

import java.util.Objects;

/** 失败分类所需的计划、步骤、工具结果和已重试次数。 */
public record FailureContext(
        AgentPlan plan,
        PlanStep step,
        ToolResult failure,
        int priorRetries) {

    public FailureContext {
        plan = Objects.requireNonNull(plan, "plan must not be null");
        step = Objects.requireNonNull(step, "step must not be null");
        failure = Objects.requireNonNull(failure, "failure must not be null");
        if (failure.success()) {
            throw new IllegalArgumentException("failure result must not be successful");
        }
        if (priorRetries < 0) {
            throw new IllegalArgumentException("priorRetries must not be negative");
        }
    }
}
