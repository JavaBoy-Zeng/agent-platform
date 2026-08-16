package com.github.agentos.memory;

/** 外部记忆模型、Embedding 或存储适配器调用失败。 */
public final class MemoryAdapterException extends RuntimeException {

    /**
     * 使用错误说明创建适配器异常。
     *
     * @param message 不包含凭据的错误说明
     */
    public MemoryAdapterException(String message) {
        super(message);
    }

    /**
     * 使用错误说明和原始原因创建适配器异常。
     *
     * @param message 不包含凭据的错误说明
     * @param cause 原始异常
     */
    public MemoryAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
