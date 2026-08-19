package com.github.agentos.kernel.eval;

import java.util.List;

/**
 * 一次评估的完整结果。
 *
 * @param caseId 评估用例标识
 * @param invocationId 被评估的 Invocation
 * @param passed 全部检查是否通过
 * @param score 通过检查数占已执行检查数的比例（无检查时为 1.0）
 * @param findings 逐项检查明细（含通过项）
 * @param actualToolSequence 实际工具名称序列
 * @param finalResponse 最终回答文本；运行未收口时为空字符串
 * @param toolCallCount 工具调用总数
 * @param failedToolCallCount 其中失败的调用数
 */
public record EvaluationResult(
        String caseId,
        String invocationId,
        boolean passed,
        double score,
        List<EvalFinding> findings,
        List<String> actualToolSequence,
        String finalResponse,
        int toolCallCount,
        int failedToolCallCount) {

    /** 规范化集合与文本；passed/score 由评估器统一计算后传入。 */
    public EvaluationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        actualToolSequence = actualToolSequence == null
                ? List.of() : List.copyOf(actualToolSequence);
        finalResponse = finalResponse == null ? "" : finalResponse;
    }

    /** 单项检查结论。 */
    public record EvalFinding(String check, boolean passed, String detail) {
    }
}
