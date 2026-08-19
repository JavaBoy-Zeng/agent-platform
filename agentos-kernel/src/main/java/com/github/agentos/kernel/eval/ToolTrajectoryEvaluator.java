package com.github.agentos.kernel.eval;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 按“路径优先”思路评估一次 Agent 运行的评估器。
 *
 * <p>输入是单次 Invocation 的完整事件流：先由 {@link ToolTrajectory}
 * 还原工具调用轨迹，再逐项比对 {@link EvalCase} 声明的期望——
 * 期望工具按序子序列、禁用工具缺席、调用预算、回答关键词与运行收口。
 * 单项未声明的检查自动跳过；全部已执行检查通过才算通过。</p>
 */
public final class ToolTrajectoryEvaluator {

    /** 创建评估器；当前无状态，可安全复用。 */
    public ToolTrajectoryEvaluator() {
    }

    /**
     * 评估一次运行。
     *
     * @param evalCase     评估用例（期望规格）
     * @param invocationId 被评估的 Invocation 标识（仅透传到结果）
     * @param events       单次 Invocation 的完整事件流；{@code null} 视为无事件
     * @return 逐项明细齐全的评估结果
     */
    public EvaluationResult evaluate(EvalCase evalCase, String invocationId, List<AgentEvent> events) {
        Objects.requireNonNull(evalCase, "evalCase must not be null");
        List<AgentEvent> safeEvents = events == null ? List.of() : events;
        ToolTrajectory trajectory = ToolTrajectory.fromEvents(safeEvents);
        String finalResponse = finalResponse(safeEvents);
        boolean completed = safeEvents.stream()
                .anyMatch(event -> event.type() == AgentEventType.AGENT_COMPLETED);

        List<EvaluationResult.EvalFinding> findings = new ArrayList<>();
        checkExpectedSequence(evalCase, trajectory, findings);
        checkForbiddenTools(evalCase, trajectory, findings);
        checkBudget(evalCase, trajectory, findings);
        checkKeywords(evalCase, finalResponse, findings);
        checkCompleted(evalCase, completed, findings);

        boolean passed = findings.stream().allMatch(EvaluationResult.EvalFinding::passed);
        double score = findings.isEmpty()
                ? 1.0
                : (double) findings.stream().filter(EvaluationResult.EvalFinding::passed).count()
                        / findings.size();
        return new EvaluationResult(
                evalCase.caseId(), invocationId, passed, score, List.copyOf(findings),
                trajectory.toolNames(), finalResponse,
                trajectory.calls().size(), (int) trajectory.failedCount());
    }

    private void checkExpectedSequence(
            EvalCase evalCase, ToolTrajectory trajectory,
            List<EvaluationResult.EvalFinding> findings) {
        List<String> expected = evalCase.expectedToolSequence();
        if (expected.isEmpty()) {
            return;
        }
        boolean match = isSubsequence(expected, trajectory.toolNames());
        findings.add(new EvaluationResult.EvalFinding(
                "expectedToolSequence", match,
                match
                        ? "期望工具序列 " + expected + " 是实际轨迹的有序子序列"
                        : "实际工具序列 " + trajectory.toolNames()
                                + " 不包含期望子序列 " + expected));
    }

    private void checkForbiddenTools(
            EvalCase evalCase, ToolTrajectory trajectory,
            List<EvaluationResult.EvalFinding> findings) {
        List<String> forbidden = evalCase.forbiddenTools();
        if (forbidden.isEmpty()) {
            return;
        }
        List<String> violated = trajectory.toolNames().stream()
                .filter(forbidden::contains)
                .distinct()
                .toList();
        findings.add(new EvaluationResult.EvalFinding(
                "forbiddenTools", violated.isEmpty(),
                violated.isEmpty()
                        ? "未调用任何禁用工具"
                        : "轨迹中出现了禁用工具: " + violated));
    }

    private void checkBudget(
            EvalCase evalCase, ToolTrajectory trajectory,
            List<EvaluationResult.EvalFinding> findings) {
        Integer budget = evalCase.maxToolCalls();
        if (budget == null) {
            return;
        }
        int count = trajectory.calls().size();
        findings.add(new EvaluationResult.EvalFinding(
                "maxToolCalls", count <= budget,
                "工具调用 " + count + " 次，预算上限 " + budget + " 次"));
    }

    private void checkKeywords(
            EvalCase evalCase, String finalResponse,
            List<EvaluationResult.EvalFinding> findings) {
        List<String> keywords = evalCase.requiredResponseKeywords();
        if (keywords.isEmpty()) {
            return;
        }
        String lower = finalResponse.toLowerCase(Locale.ROOT);
        List<String> missing = keywords.stream()
                .filter(keyword -> !lower.contains(keyword.toLowerCase(Locale.ROOT)))
                .toList();
        findings.add(new EvaluationResult.EvalFinding(
                "requiredResponseKeywords", missing.isEmpty(),
                missing.isEmpty()
                        ? "最终回答包含全部必需关键词"
                        : "最终回答缺少关键词: " + missing));
    }

    private void checkCompleted(
            EvalCase evalCase, boolean completed,
            List<EvaluationResult.EvalFinding> findings) {
        if (!evalCase.requireCompleted()) {
            return;
        }
        findings.add(new EvaluationResult.EvalFinding(
                "requireCompleted", completed,
                completed
                        ? "运行已到达 AGENT_COMPLETED"
                        : "运行未到达 AGENT_COMPLETED"));
    }

    /** 判断 expected 是否为 actual 的有序子序列（允许中间插入其他元素）。 */
    private static boolean isSubsequence(List<String> expected, List<String> actual) {
        int cursor = 0;
        for (String name : actual) {
            if (cursor < expected.size() && name.equals(expected.get(cursor))) {
                cursor++;
            }
        }
        return cursor == expected.size();
    }

    /** 取最后一条 AGENT_COMPLETED 事件的 message 作为最终回答。 */
    private static String finalResponse(List<AgentEvent> events) {
        String response = "";
        for (AgentEvent event : events) {
            if (event.type() == AgentEventType.AGENT_COMPLETED) {
                response = event.message();
            }
        }
        return response == null ? "" : response;
    }
}
