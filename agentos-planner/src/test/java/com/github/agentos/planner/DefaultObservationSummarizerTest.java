package com.github.agentos.planner;

import com.github.agentos.tool.ToolFailureType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultObservationSummarizerTest {

    @Test
    void keepsHeadAndTailWithinPerObservationBudget() {
        DefaultObservationSummarizer summarizer =
                new DefaultObservationSummarizer(80, 160);
        StepResult result = result("step-1", "A".repeat(100) + "TAIL");

        Observation observation = summarizer.summarize(List.of(result)).getFirst();

        assertThat(observation.summary())
                .hasSize(80)
                .startsWith("A")
                .contains("observation summarized by runtime")
                .endsWith("TAIL");
    }

    @Test
    void givesPriorityToNewestResultsWithinTotalBudget() {
        DefaultObservationSummarizer summarizer =
                new DefaultObservationSummarizer(64, 70);

        List<Observation> observations = summarizer.summarize(List.of(
                result("old", "O".repeat(64)),
                result("new", "N".repeat(64))));

        assertThat(observations).extracting(Observation::stepId)
                .containsExactly("old", "new");
        assertThat(observations.getFirst().summary()).hasSize(6);
        assertThat(observations.getLast().summary()).hasSize(64);
        assertThat(observations).extracting(observation -> observation.summary().length())
                .containsExactly(6, 64);
    }

    private static StepResult result(String stepId, String output) {
        return new StepResult(
                "plan-1", stepId, "file_read", StepStatus.COMPLETED, output, "",
                ToolFailureType.NONE, 1);
    }
}
