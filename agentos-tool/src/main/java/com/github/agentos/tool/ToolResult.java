package com.github.agentos.tool;

/**
 * 工具执行后的标准结果。
 *
 * @param success 是否执行成功
 * @param output 成功时的输出内容
 * @param error 失败时的错误信息
 */
public record ToolResult(boolean success, String output, String error) {

    /**
     * 创建工具结果，并将空输出或空错误转换为空字符串。
     */
    public ToolResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    /**
     * 创建成功结果。
     *
     * @param output 工具输出
     * @return 成功的工具结果
     */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, "");
    }

    /**
     * 创建失败结果。
     *
     * @param error 失败原因
     * @return 失败的工具结果
     */
    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error);
    }
}
