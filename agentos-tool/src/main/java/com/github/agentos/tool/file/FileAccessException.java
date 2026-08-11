package com.github.agentos.tool.file;

import com.github.agentos.tool.ToolFailureType;

import java.util.Objects;

/** 文件访问策略拒绝请求时携带结构化失败类型的异常。 */
public final class FileAccessException extends RuntimeException {

    private final ToolFailureType failureType;

    public FileAccessException(ToolFailureType failureType, String message) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        if (failureType == ToolFailureType.NONE) {
            throw new IllegalArgumentException("failureType must describe a failure");
        }
    }

    public ToolFailureType failureType() {
        return failureType;
    }
}
