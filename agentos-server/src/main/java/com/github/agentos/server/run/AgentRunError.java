package com.github.agentos.server.run;

/** 稳定的运行或工具错误对象；客户端不得解析人类可读 message 判断错误类型。 */
public record AgentRunError(String code, String message, boolean retryable) {
    public AgentRunError {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        code = code.trim();
        message = message == null ? "" : message;
    }
}
