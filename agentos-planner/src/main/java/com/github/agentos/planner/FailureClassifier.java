package com.github.agentos.planner;

/** 将结构化工具失败映射为 Runtime 控制动作。 */
public interface FailureClassifier {

    FailureDecision classify(FailureContext context);
}
