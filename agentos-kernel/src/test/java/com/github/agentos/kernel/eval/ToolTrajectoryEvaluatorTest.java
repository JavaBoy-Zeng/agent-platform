package com.github.agentos.kernel.eval;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTrajectoryEvaluatorTest {

    private final ToolTrajectoryEvaluator evaluator = new ToolTrajectoryEvaluator();

    @Test
    void allDeclaredChecksPass_yieldsPassedWithFullScore() {
        EvalCase evalCase = new EvalCase("case-1", "搜索并回答",
                List.of("web_search"), List.of("file_write"), 5,
                List.of("MiniMax"), true);
        List<AgentEvent> events = run(
                tool("web_search"), completed("MiniMax 发布了新模型。"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.findings()).hasSize(5);
        assertThat(result.findings()).allMatch(EvaluationResult.EvalFinding::passed);
        assertThat(result.actualToolSequence()).containsExactly("web_search");
        assertThat(result.finalResponse()).isEqualTo("MiniMax 发布了新模型。");
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.failedToolCallCount()).isZero();
    }

    @Test
    void expectedSequence_allowsExtraCallsButRequiresOrder() {
        EvalCase evalCase = EvalCase.ofTools("case-2",
                List.of("web_search", "file_read"));
        List<AgentEvent> events = run(
                tool("web_search"), tool("file_read"), tool("web_search"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void expectedSequence_outOfOrderFails() {
        EvalCase evalCase = EvalCase.ofTools("case-3",
                List.of("file_read", "web_search"));
        List<AgentEvent> events = run(tool("web_search"), tool("file_read"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isZero();
        assertThat(finding(result, "expectedToolSequence").passed()).isFalse();
    }

    @Test
    void forbiddenToolPresent_failsCheck() {
        EvalCase evalCase = new EvalCase("case-4", "",
                List.of(), List.of("file_write"), null, List.of(), false);
        List<AgentEvent> events = run(tool("file_write"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(finding(result, "forbiddenTools").passed()).isFalse();
    }

    @Test
    void budgetExceeded_failsCheck() {
        EvalCase evalCase = new EvalCase("case-5", "",
                List.of(), List.of(), 2, List.of(), false);
        List<AgentEvent> events = run(tool("a"), tool("b"), tool("c"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(finding(result, "maxToolCalls").passed()).isFalse();
        assertThat(result.toolCallCount()).isEqualTo(3);
    }

    @Test
    void keywords_matchCaseInsensitively() {
        EvalCase evalCase = new EvalCase("case-6", "",
                List.of(), List.of(), null, List.of("minimax", "模型"), false);
        List<AgentEvent> events = run(completed("MiniMax 发布了新模型"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(finding(result, "requiredResponseKeywords").passed()).isTrue();
    }

    @Test
    void keywords_missingKeywordFails() {
        EvalCase evalCase = new EvalCase("case-7", "",
                List.of(), List.of(), null, List.of("价格"), false);
        List<AgentEvent> events = run(completed("今天天气晴朗"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(finding(result, "requiredResponseKeywords").passed()).isFalse();
    }

    @Test
    void requireCompleted_failsWhenRunDidNotComplete() {
        EvalCase evalCase = new EvalCase("case-8", "",
                List.of(), List.of(), null, List.of(), true);
        List<AgentEvent> events = run(
                tool("web_search"),
                failed("模型调用超时"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(finding(result, "requireCompleted").passed()).isFalse();
        assertThat(result.finalResponse()).isEmpty();
    }

    @Test
    void score_isPassedRatioOverExecutedChecks() {
        // 声明两项检查：子序列通过、预算超限失败 → score 0.5。
        EvalCase evalCase = new EvalCase("case-9", "",
                List.of("web_search"), List.of(), 1, List.of(), false);
        List<AgentEvent> events = run(tool("web_search"), tool("file_read"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", events);

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void noDeclaredChecks_passesWithFullScore() {
        EvalCase evalCase = new EvalCase("case-10", "",
                List.of(), List.of(), null, List.of(), false);

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", List.of());

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void nullEvents_treatedAsEmptyRun() {
        EvalCase evalCase = EvalCase.ofTools("case-11", List.of("web_search"));

        EvaluationResult result = evaluator.evaluate(evalCase, "inv-1", null);

        assertThat(result.passed()).isFalse();
        assertThat(result.actualToolSequence()).isEmpty();
        assertThat(result.toolCallCount()).isZero();
    }

    private static EvaluationResult.EvalFinding finding(
            EvaluationResult result, String check) {
        return result.findings().stream()
                .filter(f -> f.check().equals(check))
                .findFirst()
                .orElseThrow();
    }

    /** 构造一次运行的完整事件流（AGENT_STARTED + 各观察点）。 */
    private static List<AgentEvent> run(AgentEvent... observations) {
        List<AgentEvent> events = new ArrayList<>();
        events.add(event(AgentEventType.AGENT_STARTED, "run", Map.of()));
        events.addAll(List.of(observations));
        return events;
    }

    private static AgentEvent tool(String name) {
        return event(AgentEventType.TOOL_CALL_STARTED, name,
                Map.of("toolName", name));
    }

    private static AgentEvent completed(String output) {
        return event(AgentEventType.AGENT_COMPLETED, output, Map.of("status", "COMPLETED"));
    }

    private static AgentEvent failed(String error) {
        return event(AgentEventType.AGENT_FAILED, error, Map.of("status", "FAILED"));
    }

    private static AgentEvent event(
            AgentEventType type, String message, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        return new DefaultAgentEvent(
                "event-" + Math.random(), "session-1", "inv-1", "main-agent",
                Instant.now(), type, message == null ? "" : message, payload);
    }
}
