package com.github.agentos.server.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent REST 接口的统一异常转换器。
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    /**
     * 创建 REST 异常转换器。
     */
    public AgentExceptionHandler() {
    }

    /**
     * 将非法参数异常转换为 HTTP 400 问题详情响应。
     *
     * @param exception 捕获到的非法参数异常
     * @return 符合 Problem Detail 格式的错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid agent request");
        detail.setDetail(exception.getMessage());
        return detail;
    }
}
