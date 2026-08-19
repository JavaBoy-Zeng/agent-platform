package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolFailureType;

import java.util.List;

/**
 * 用于验证 AgentOS 完整调用链的内置回显工具。
 *
 * <p>该工具读取 {@code message} 参数并原样返回，不产生外部副作用，因此使用默认低风险等级。</p>
 */
public final class EchoTool implements AgentTool {

    /**
     * 创建回显工具。
     */
    public EchoTool() {
    }

    /**
     * 获取回显工具名称。
     *
     * @return 固定工具名称 {@code echo}
     */
    @Override
    public String name() {
        return "echo";
    }

    /**
     * 获取回显工具说明。
     *
     * @return 回显工具的功能说明
     */
    @Override
    public String description() {
        return "Returns the supplied message";
    }

    /**
     * 获取回显工具的参数结构。
     *
     * @return 只包含必填字符串参数 {@code message} 的定义
     */
    @Override
    public List<ToolParameter> parameters() {
        return List.of(new ToolParameter(
                "message",
                ToolParameter.ValueType.STRING,
                "需要原样返回的消息",
                true));
    }

    /**
     * 执行回显操作。
     *
     * @param call 包含 {@code message} 参数的工具调用
     * @return 参数存在时返回成功结果，否则返回缺少参数的失败结果
     */
    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        Object message = call.arguments().get("message");
        return message == null
                ? ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "missing required argument: message")
                : ToolResult.success(message.toString());
    }
}
