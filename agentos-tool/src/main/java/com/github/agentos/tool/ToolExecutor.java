package com.github.agentos.tool;

import java.util.Objects;

/**
 * 工具调用执行器。
 *
 * <p>执行器通过注册表解析工具，并将工具查找失败、执行异常或空返回值统一转换为
 * {@link ToolResult} 失败结果，使异常不会越过工具执行边界。</p>
 */
public final class ToolExecutor {

    private final ToolRegistry registry;

    /**
     * 创建工具执行器。
     *
     * @param registry 工具注册表
     * @throws NullPointerException 当注册表为 {@code null} 时抛出
     */
    public ToolExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 执行一次工具调用。
     *
     * @param call 工具调用请求
     * @return 成功或失败的标准工具结果
     * @throws NullPointerException 当调用请求为 {@code null} 时抛出
     */
    public ToolResult execute(ToolCall call) {
        Objects.requireNonNull(call, "call must not be null");
        try {
            return Objects.requireNonNull(
                    registry.require(call.toolName()).execute(call),
                    "tool returned null result");
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return ToolResult.failure(message);
        }
    }
}
