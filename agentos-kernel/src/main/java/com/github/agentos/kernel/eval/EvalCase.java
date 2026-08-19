package com.github.agentos.kernel.eval;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Agent 运行的评估用例（期望规格）。
 *
 * <p>用例以“路径优先”的思路声明期望：期望工具按序构成实际轨迹的子序列、
 * 禁止工具不得出现、调用预算不得超限、最终回答需包含关键词、运行需成功
 * 收口。全部检查通过才算通过；单项未声明的检查自动跳过。</p>
 *
 * @param caseId 用例标识
 * @param description 用例说明
 * @param expectedToolSequence 期望的工具名称有序子序列
 * @param forbiddenTools 禁止调用的工具名称
 * @param maxToolCalls 工具调用预算上限；null 表示不限制
 * @param requiredResponseKeywords 最终回答必须包含的关键词（忽略大小写）
 * @param requireCompleted 是否要求运行到达 AGENT_COMPLETED
 */
public record EvalCase(
        String caseId,
        String description,
        List<String> expectedToolSequence,
        List<String> forbiddenTools,
        Integer maxToolCalls,
        List<String> requiredResponseKeywords,
        boolean requireCompleted) {

    /** 规范化空集合并校验用例标识。 */
    public EvalCase {
        caseId = caseId == null || caseId.isBlank() ? "default" : caseId.strip();
        description = description == null ? "" : description;
        expectedToolSequence = expectedToolSequence == null
                ? List.of() : List.copyOf(expectedToolSequence);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        requiredResponseKeywords = requiredResponseKeywords == null
                ? List.of() : List.copyOf(requiredResponseKeywords);
        if (maxToolCalls != null && maxToolCalls <= 0) {
            throw new IllegalArgumentException("maxToolCalls must be positive");
        }
    }

    /** 创建仅校验期望工具子序列的用例。 */
    public static EvalCase ofTools(String caseId, List<String> expectedToolSequence) {
        return new EvalCase(caseId, "", expectedToolSequence,
                List.of(), null, List.of(), false);
    }
}
