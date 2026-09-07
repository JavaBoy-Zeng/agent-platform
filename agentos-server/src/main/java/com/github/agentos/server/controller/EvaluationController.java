package com.github.agentos.server.controller;

import com.github.agentos.kernel.eval.EvalCase;
import com.github.agentos.kernel.eval.EvaluationResult;
import com.github.agentos.server.eval.EvaluationService;
import com.github.agentos.server.security.SessionAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 运行评估接口。
 *
 * <p>对已结束（或进行中）的某次 Invocation 提交一份评估用例，
 * 返回工具轨迹与逐项检查结论，用于回归验证 Agent 的执行路径。</p>
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final SessionAuthorization authorization;

    public EvaluationController(
            EvaluationService evaluationService, SessionAuthorization authorization) {
        this.evaluationService = evaluationService;
        this.authorization = authorization;
    }

    /**
     * 评估单次 Invocation。
     *
     * @param invocationId 被评估的 Invocation 标识
     * @param request      评估用例请求体
     * @return 评估结果；Invocation 无事件记录时返回 404
     */
    @PostMapping("/{invocationId}")
    public ResponseEntity<EvaluationResult> evaluate(
            @PathVariable String invocationId,
            @RequestBody EvalCaseRequest request,
            HttpServletRequest httpRequest) {
        String sessionId = evaluationService.sessionId(invocationId).orElse(null);
        if (sessionId == null) return ResponseEntity.notFound().build();
        authorization.requireOwned(sessionId, httpRequest);
        return evaluationService.evaluate(invocationId, request.toCase())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 评估用例 REST 请求体；字段语义与 {@link EvalCase} 一致。 */
    public record EvalCaseRequest(
            String caseId,
            String description,
            List<String> expectedToolSequence,
            List<String> forbiddenTools,
            Integer maxToolCalls,
            List<String> requiredResponseKeywords,
            Boolean requireCompleted) {

        EvalCase toCase() {
            return new EvalCase(caseId, description, expectedToolSequence, forbiddenTools,
                    maxToolCalls, requiredResponseKeywords,
                    Boolean.TRUE.equals(requireCompleted));
        }
    }
}
