package com.github.agentos.planner;

import java.util.Objects;

/** 失败分类器给出的控制动作和可选重规划原因。 */
public record FailureDecision(FailureAction action, ReplanReason replanReason) {

    public FailureDecision {
        action = Objects.requireNonNull(action, "action must not be null");
        if ((action == FailureAction.REPLAN) != (replanReason != null)) {
            throw new IllegalArgumentException("only REPLAN decision must contain replanReason");
        }
    }

    public static FailureDecision retry() {
        return new FailureDecision(FailureAction.RETRY, null);
    }

    public static FailureDecision skip() {
        return new FailureDecision(FailureAction.SKIP, null);
    }

    public static FailureDecision replan(ReplanReason reason) {
        return new FailureDecision(FailureAction.REPLAN, reason);
    }

    public static FailureDecision abort() {
        return new FailureDecision(FailureAction.ABORT, null);
    }
}
