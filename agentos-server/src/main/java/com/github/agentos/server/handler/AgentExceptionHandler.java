package com.github.agentos.server.handler;

import com.github.agentos.kernel.AgentRunRejectedException;
import com.github.agentos.server.model.ModelProviderService.ProviderInUseException;
import com.github.agentos.server.model.ModelProviderService.ProviderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent REST 接口的统一异常转换器。
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    /** 将 Runner 的并发保护映射为稳定、可重试的 HTTP 状态。 */
    @ExceptionHandler(AgentRunRejectedException.class)
    ProblemDetail handleRunRejected(AgentRunRejectedException exception) {
        HttpStatus status = exception.reason()
                == AgentRunRejectedException.Reason.SESSION_BUSY
                ? HttpStatus.CONFLICT : HttpStatus.TOO_MANY_REQUESTS;
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle("Agent run rejected");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    /** 异步执行队列已满时返回 429，避免把资源耗尽伪装成服务端故障。 */
    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    ProblemDetail handleExecutionRejected(
            java.util.concurrent.RejectedExecutionException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Agent run queue is full");
        detail.setDetail("Too many agent runs are active or queued; retry later");
        return detail;
    }

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

    @ExceptionHandler(ProviderNotFoundException.class)
    ProblemDetail handleProviderNotFound(ProviderNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Model Provider not found");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    @ExceptionHandler(ProviderInUseException.class)
    ProblemDetail handleProviderInUse(ProviderInUseException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Model Provider is in use");
        detail.setDetail(exception.getMessage());
        return detail;
    }
}
