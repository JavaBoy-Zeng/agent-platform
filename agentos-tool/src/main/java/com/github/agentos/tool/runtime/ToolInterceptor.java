package com.github.agentos.tool.runtime;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;

/** 工具调用前后和异常阶段的可组合生命周期扩展点。 */
public interface ToolInterceptor {

    /** 工具执行前运行；可返回短路结果阻止真实工具调用。 */
    default ToolBeforeResult beforeExecute(ToolCall call, ToolExecutionContext context) {
        return ToolBeforeResult.allow();
    }

    /** 工具成功返回标准结果后运行；可替换或增强结果。 */
    default ToolResult afterExecute(
            ToolCall call, ToolResult result, ToolExecutionContext context) {
        return result;
    }

    /** 工具或拦截器抛出异常时运行；可将异常转换为标准结果。 */
    default ToolResult onError(
            ToolCall call, Throwable error, ToolExecutionContext context) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, message);
    }
}
