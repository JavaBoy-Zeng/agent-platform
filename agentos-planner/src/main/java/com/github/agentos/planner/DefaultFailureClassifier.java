package com.github.agentos.planner;

import com.github.agentos.tool.ToolFailureType;

import java.util.Objects;

/** 默认失败策略，安全和内部错误始终终止，只有明确可恢复失败才重规划。 */
public final class DefaultFailureClassifier implements FailureClassifier {

    @Override
    public FailureDecision classify(FailureContext context) {
        Objects.requireNonNull(context, "context must not be null");
        ToolFailureType type = context.failure().failureType();
        return switch (type) {
            case ACCESS_DENIED, PERMISSION_DENIED, SECURITY_DENIED,
                    TOOL_INTERNAL_ERROR, UNKNOWN, NONE -> FailureDecision.abort();
            case TRANSIENT -> context.priorRetries() == 0
                    ? FailureDecision.retry()
                    : optionalOrReplan(context, ReplanReason.RECOVERABLE_FAILURE);
            case NOT_FOUND -> optionalOrReplan(context, ReplanReason.INVALID_ASSUMPTION);
            case INVALID_ARGUMENT -> optionalOrReplan(context, ReplanReason.RECOVERABLE_FAILURE);
        };
    }

    private static FailureDecision optionalOrReplan(
            FailureContext context, ReplanReason reason) {
        return context.step().optional()
                ? FailureDecision.skip()
                : FailureDecision.replan(reason);
    }
}
