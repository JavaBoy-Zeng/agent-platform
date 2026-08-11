package com.github.agentos.planner;

import java.util.List;

/** 将原始工具步骤结果转换成提供给 Planner 和记忆系统的有界观察摘要。 */
@FunctionalInterface
public interface ObservationSummarizer {

    /**
     * 摘要化累计步骤结果。
     *
     * @param stepResults 本次 Agent 运行已完成的全部步骤结果
     * @return 按执行顺序排列的有界观察结果
     */
    List<Observation> summarize(List<StepResult> stepResults);
}
