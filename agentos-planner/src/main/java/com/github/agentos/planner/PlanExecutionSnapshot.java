package com.github.agentos.planner;

import java.util.List;
import java.util.Objects;

/**
 * 供重规划使用的累计执行快照。
 *
 * @param stepResults 整次运行中所有已经结束的步骤结果
 * @param observations 提供给 Planner 的有界累计观察摘要
 * @param currentStep 触发本次重规划的最近步骤
 * @param lastResult 最近步骤结果
 * @param reason 重规划原因
 */
public record PlanExecutionSnapshot(
        List<StepResult> stepResults,
        List<Observation> observations,
        PlanStep currentStep,
        StepResult lastResult,
        ReplanReason reason) {

    /** 创建并复制执行快照。 */
    public PlanExecutionSnapshot {
        stepResults = List.copyOf(Objects.requireNonNull(stepResults, "stepResults must not be null"));
        observations = List.copyOf(Objects.requireNonNull(
                observations, "observations must not be null"));
        currentStep = Objects.requireNonNull(currentStep, "currentStep must not be null");
        lastResult = Objects.requireNonNull(lastResult, "lastResult must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }
}
