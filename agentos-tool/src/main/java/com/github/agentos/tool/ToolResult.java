package com.github.agentos.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具执行后的标准结果。
 *
 * @param status 结构化执行状态
 * @param data 成功时的结构化数据，可为字符串或领域对象
 * @param message 面向人和日志的简短说明
 * @param failureType 失败分类；成功时固定为 {@link ToolFailureType#NONE}
 * @param metadata 工具级扩展元数据
 * @param actions 返回给 Runtime 的控制动作
 */
public record ToolResult(
        ToolStatus status,
        Object data,
        String message,
        ToolFailureType failureType,
        Map<String, Object> metadata,
        ToolActions actions) {

    /**
     * 创建工具结果，并将空输出或空错误转换为空字符串。
     */
    public ToolResult {
        status = Objects.requireNonNull(status, "status must not be null");
        message = message == null ? "" : message;
        failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        actions = actions == null ? ToolActions.none() : actions;
        if (status == ToolStatus.SUCCESS && failureType != ToolFailureType.NONE) {
            throw new IllegalArgumentException("successful result must have failureType NONE");
        }
        if (status == ToolStatus.FAILURE && failureType == ToolFailureType.NONE) {
            throw new IllegalArgumentException("failed result must describe a failure");
        }
    }

    /** 保留旧版四参数构造 API，便于现有工具增量迁移。 */
    public ToolResult(
            boolean success, String output, String error, ToolFailureType failureType) {
        this(
                success ? ToolStatus.SUCCESS : ToolStatus.FAILURE,
                success ? (output == null ? "" : output) : null,
                success ? "" : (error == null ? "" : error),
                failureType,
                Map.of(),
                ToolActions.none());
    }

    /** 返回是否成功，保持现有调用代码兼容。 */
    public boolean success() {
        return status == ToolStatus.SUCCESS;
    }

    /** 将结构化数据转换为旧版文本输出。 */
    public String output() {
        return data == null ? "" : data instanceof String text ? text : String.valueOf(data);
    }

    /** 返回旧版错误文本；成功结果固定为空。 */
    public String error() {
        return success() ? "" : message;
    }

    /**
     * 创建成功结果。
     *
     * @param output 工具输出
     * @return 成功的工具结果
     */
    public static ToolResult success(String output) {
        return success((Object) output);
    }

    /** 创建携带任意结构化数据的成功结果。 */
    public static ToolResult success(Object data) {
        return new ToolResult(
                ToolStatus.SUCCESS, data, "", ToolFailureType.NONE,
                Map.of(), ToolActions.none());
    }

    /** 创建携带元数据与 Runtime 控制动作的成功结果。 */
    public static ToolResult success(
            Object data, String message, Map<String, Object> metadata, ToolActions actions) {
        return new ToolResult(
                ToolStatus.SUCCESS, data, message, ToolFailureType.NONE, metadata, actions);
    }

    /**
     * 创建失败结果。
     *
     * @param error 失败原因
     * @return 失败的工具结果
     */
    public static ToolResult failure(String error) {
        return failure(ToolFailureType.UNKNOWN, error);
    }

    /** 创建带结构化分类的失败结果。 */
    public static ToolResult failure(ToolFailureType failureType, String error) {
        return new ToolResult(
                ToolStatus.FAILURE, null, error, failureType,
                Map.of(), ToolActions.none());
    }

    /** 创建要求 Runtime 挂起的工具结果。 */
    public static ToolResult pending(com.github.agentos.kernel.PendingAction action) {
        return new ToolResult(
                ToolStatus.SUCCESS, null, action.description(), ToolFailureType.NONE,
                Map.of(), ToolActions.pending(action));
    }
}
