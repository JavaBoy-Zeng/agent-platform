package com.github.agentos.server.handler;

import com.github.agentos.kernel.AgentRunRejectedException;
import com.github.agentos.server.model.ModelProviderService.DuplicateModelException;
import com.github.agentos.server.model.ModelProviderService.ModelNotFoundException;
import com.github.agentos.server.model.ModelProviderService.ProviderNotFoundException;
import com.github.agentos.server.model.NonAdminCallRateLimitException;
import com.github.agentos.server.automation.AutomationService.AutomationConflictException;
import com.github.agentos.server.automation.AutomationService.AutomationNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(ModelNotFoundException.class)
    ProblemDetail handleModelNotFound(ModelNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Model not found");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    @ExceptionHandler(DuplicateModelException.class)
    ProblemDetail handleDuplicateModel(DuplicateModelException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Model already exists");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    @ExceptionHandler(AutomationNotFoundException.class)
    ProblemDetail handleAutomationNotFound(AutomationNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Automation not found");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    @ExceptionHandler(AutomationConflictException.class)
    ProblemDetail handleAutomationConflict(AutomationConflictException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Automation conflict");
        detail.setDetail(exception.getMessage());
        return detail;
    }

    /** 非管理员模型调用被限频：429 + Retry-After，方便客户端自动退避。 */
    @ExceptionHandler(NonAdminCallRateLimitException.class)
    ResponseEntity<ProblemDetail> handleNonAdminRateLimit(NonAdminCallRateLimitException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Non-admin model call rate limit");
        detail.setDetail(exception.getMessage());
        long retryAfterSeconds = Math.max(1L,
                (exception.retryAfterMillis() + 999L) / 1000L);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(detail);
    }
}
