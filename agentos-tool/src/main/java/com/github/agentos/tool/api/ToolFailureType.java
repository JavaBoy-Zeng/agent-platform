package com.github.agentos.tool.api;

/**
 * 工具失败的结构化分类，供 Runtime 决定重试、跳过、重规划或终止。
 */
public enum ToolFailureType {
    /** 没有失败。 */
    NONE,
    /** 运行已被用户取消，工具未执行或中途终止。 */
    CANCELLED,
    /** 工具参数不合法。 */
    INVALID_ARGUMENT,
    /** 目标资源不存在。 */
    NOT_FOUND,
    /** 可能通过短暂重试恢复的失败。 */
    TRANSIENT,
    /** 操作超过时间限制被终止。 */
    TIMEOUT,
    /** 目标不可访问。 */
    ACCESS_DENIED,
    /** 操作系统或资源权限不足。 */
    PERMISSION_DENIED,
    /** 安全策略拒绝访问。 */
    SECURITY_DENIED,
    /** 工具实现或 Runtime 内部异常。 */
    TOOL_INTERNAL_ERROR,
    /** 未分类失败。 */
    UNKNOWN
}
