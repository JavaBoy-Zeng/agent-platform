package com.github.agentos.tool.code;

import java.util.Objects;

/**
 * 一次代码执行请求。
 *
 * @param language       代码语言
 * @param code           代码正文
 * @param timeoutSeconds 执行超时秒数；非正数表示由执行器使用默认值
 */
public record CodeExecutionRequest(CodeLanguage language, String code, int timeoutSeconds) {

    /** 校验必填字段。 */
    public CodeExecutionRequest {
        Objects.requireNonNull(language, "language must not be null");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must not be negative");
        }
    }

    /** 创建使用执行器默认超时的请求。 */
    public static CodeExecutionRequest of(CodeLanguage language, String code) {
        return new CodeExecutionRequest(language, code, 0);
    }
}
