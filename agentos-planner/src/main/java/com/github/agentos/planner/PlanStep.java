package com.github.agentos.planner;

import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolExecutionMode;

import java.util.List;
import java.util.Objects;

/**
 * 一条不可变计划步骤。
 *
 * @param id 步骤标识
 * @param description 步骤用途说明
 * @param optional 失败后是否允许 FailureClassifier 跳过
 * @param toolCalls 工具调用列表
 * @param executionMode 列表调度模式
 */
public record PlanStep(
        String id,
        String description,
        boolean optional,
        List<ToolCall> toolCalls,
        ToolExecutionMode executionMode) {

    /** 创建并校验计划步骤。 */
    public PlanStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("step id must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("step description must not be blank");
        }
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        if (toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be empty");
        }
        toolCalls.forEach(call -> Objects.requireNonNull(call, "toolCall must not be null"));
        executionMode = Objects.requireNonNull(executionMode, "executionMode must not be null");
    }

    /** 保留旧版单工具构造方法并自动转换为顺序调用列表。 */
    public PlanStep(String id, String description, boolean optional, ToolCall toolCall) {
        this(id, description, optional, List.of(toolCall), ToolExecutionMode.SEQUENTIAL);
    }

    /** 返回首个工具调用，保持现有规划器与日志 API 兼容。 */
    public ToolCall toolCall() {
        return toolCalls.getFirst();
    }
}
