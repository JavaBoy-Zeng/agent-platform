package com.github.agentos.agent.finalize;
import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanType;

import java.util.Objects;

/** 校验 COMPLETE 领域约束并对最终回答设置输出上限。 */
public final class DefaultAgentFinalizer implements AgentFinalizer {

    public static final int DEFAULT_MAX_ANSWER_LENGTH = 100_000;
    private static final String TRUNCATED = "\n[final answer truncated by runtime]";
    private final int maxAnswerLength;

    public DefaultAgentFinalizer() {
        this(DEFAULT_MAX_ANSWER_LENGTH);
    }

    public DefaultAgentFinalizer(int maxAnswerLength) {
        if (maxAnswerLength <= TRUNCATED.length()) {
            throw new IllegalArgumentException("maxAnswerLength is too small");
        }
        this.maxAnswerLength = maxAnswerLength;
    }

    @Override
    public String finish(
            AgentRequest request, AgentContext context, AgentPlan completedPlan) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(completedPlan, "completedPlan must not be null");
        if (completedPlan.outcome() != PlanOutcome.COMPLETE
                || completedPlan.type() != PlanType.EXECUTION
                || completedPlan.finalAnswer().isBlank()) {
            throw new IllegalArgumentException(
                    "finalizer requires an EXECUTION/COMPLETE plan with finalAnswer");
        }
        String answer = completedPlan.finalAnswer().trim();
        if (answer.length() <= maxAnswerLength) {
            return answer;
        }
        return answer.substring(0, maxAnswerLength - TRUNCATED.length()) + TRUNCATED;
    }
}
