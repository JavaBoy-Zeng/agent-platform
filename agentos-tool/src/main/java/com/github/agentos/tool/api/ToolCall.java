package com.github.agentos.tool.api;

import java.util.Map;
import java.util.Objects;

/**
 * 一次不可变的工具调用请求。
 *
 * @param toolName 要调用的工具名称
 * @param arguments 传递给工具的只读参数集合
 */
public record ToolCall(String toolName, Map<String, Object> arguments) {

    /**
     * 创建并校验工具调用，同时复制参数集合以保证不可变性。
     *
     * @throws IllegalArgumentException 当工具名称为空时抛出
     * @throws NullPointerException 当参数集合为 {@code null} 时抛出
     */
    public ToolCall {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }
}
