package com.github.agentos.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 确定性 Observation 摘要器。
 *
 * <p>优先保留最新观察；单条过长时同时保留头部和尾部，便于文件读取结果保留声明信息与
 * 结尾结论。该实现不调用模型，因此不会额外消耗模型调用预算。</p>
 */
public final class DefaultObservationSummarizer implements ObservationSummarizer {

    public static final int DEFAULT_MAX_OBSERVATION_CHARS = 4_000;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 24_000;
    private static final String TRUNCATED = "\n...[observation summarized by runtime]...\n";

    private final int maxObservationChars;
    private final int maxTotalChars;

    /** 使用默认摘要预算创建摘要器。 */
    public DefaultObservationSummarizer() {
        this(DEFAULT_MAX_OBSERVATION_CHARS, DEFAULT_MAX_TOTAL_CHARS);
    }

    /** 使用指定单条与累计字符预算创建摘要器。 */
    public DefaultObservationSummarizer(int maxObservationChars, int maxTotalChars) {
        if (maxObservationChars <= TRUNCATED.length()) {
            throw new IllegalArgumentException("maxObservationChars is too small");
        }
        if (maxTotalChars < maxObservationChars) {
            throw new IllegalArgumentException(
                    "maxTotalChars must be greater than or equal to maxObservationChars");
        }
        this.maxObservationChars = maxObservationChars;
        this.maxTotalChars = maxTotalChars;
    }

    @Override
    public List<Observation> summarize(List<StepResult> stepResults) {
        Objects.requireNonNull(stepResults, "stepResults must not be null");
        List<Observation> newestFirst = new ArrayList<>();
        int remaining = maxTotalChars;
        for (int index = stepResults.size() - 1; index >= 0 && remaining > 0; index--) {
            StepResult result = Objects.requireNonNull(
                    stepResults.get(index), "stepResults must not contain null");
            String value = result.output().isBlank() ? result.error() : result.output();
            int allowed = Math.min(maxObservationChars, remaining);
            String summary = summarize(value, allowed);
            newestFirst.add(new Observation(
                    result.planId(), result.stepId(), result.toolName(), result.status(),
                    summary, result.failureType(), result.attempts()));
            remaining -= summary.length();
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private static String summarize(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maximum) {
            return normalized;
        }
        if (maximum <= TRUNCATED.length()) {
            return normalized.substring(0, maximum);
        }
        int contentBudget = maximum - TRUNCATED.length();
        int headLength = contentBudget * 3 / 4;
        int tailLength = contentBudget - headLength;
        return normalized.substring(0, headLength)
                + TRUNCATED
                + normalized.substring(normalized.length() - tailLength);
    }
}
