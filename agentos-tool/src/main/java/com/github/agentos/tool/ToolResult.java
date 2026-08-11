package com.github.agentos.tool;

/**
 * 工具执行后的标准结果。
 *
 * @param success 是否执行成功
 * @param output 成功时的输出内容
 * @param error 失败时的错误信息
 * @param failureType 失败分类；成功时固定为 {@link ToolFailureType#NONE}
 */
public record ToolResult(
        boolean success,
        String output,
        String error,
        ToolFailureType failureType) {

    /**
     * 创建工具结果，并将空输出或空错误转换为空字符串。
     */
    public ToolResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
        failureType = java.util.Objects.requireNonNull(failureType, "failureType must not be null");
        if (success && failureType != ToolFailureType.NONE) {
            throw new IllegalArgumentException("successful result must have failureType NONE");
        }
        if (!success && failureType == ToolFailureType.NONE) {
            throw new IllegalArgumentException("failed result must describe a failure");
        }
    }

    /**
     * 创建成功结果。
     *
     * @param output 工具输出
     * @return 成功的工具结果
     */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, "", ToolFailureType.NONE);
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
        return new ToolResult(false, "", error, failureType);
    }
}
