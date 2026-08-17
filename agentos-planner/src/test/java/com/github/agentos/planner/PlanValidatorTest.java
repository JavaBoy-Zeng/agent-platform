package com.github.agentos.planner;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.builtin.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证模型计划执行前的工具、参数和步骤边界。 */
class PlanValidatorTest {

    private final ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));

    @Test
    void rejectsUnknownTool() {
        AgentPlan plan = continuePlan(List.of(new PlanStep(
                "step-1", "调用未知工具", false, new ToolCall("missing", Map.of()))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("unknown tool missing");
    }

    @Test
    void rejectsMissingRequiredArgument() {
        AgentPlan plan = continuePlan(List.of(new PlanStep(
                "step-1", "缺少消息", false, new ToolCall("echo", Map.of()))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("misses required argument message");
    }

    @Test
    void rejectsWrongTypeAndUnknownArgument() {
        AgentPlan plan = continuePlan(List.of(new PlanStep(
                "step-1", "错误参数", true,
                new ToolCall("echo", Map.of("message", 42, "extra", true)))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("argument message must be STRING")
                .hasMessageContaining("unknown argument extra");
    }

    @Test
    void rejectsPlanAboveStepLimit() {
        AgentPlan plan = continuePlan(List.of(echoStep("step-1"), echoStep("step-2")));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry, 1).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("step count 2 exceeds limit 1");
    }

    @Test
    void acceptsExecutionCompleteWithoutToolSteps() {
        AgentPlan plan = AgentPlan.create(
                PlanType.EXECUTION,
                PlanOrigin.REPLANNED,
                PlanOutcome.COMPLETE,
                "回答用户",
                List.of(),
                "最终答案");

        assertThatCode(() -> new PlanValidator(toolRegistry).validate(plan))
                .doesNotThrowAnyException();
    }

    private static AgentPlan continuePlan(List<PlanStep> steps) {
        return AgentPlan.create(
                PlanType.EXECUTION,
                PlanOrigin.INITIAL,
                PlanOutcome.CONTINUE,
                "测试计划",
                steps,
                "");
    }

    private static PlanStep echoStep(String id) {
        return new PlanStep(
                id, "回显消息", false,
                new ToolCall("echo", Map.of("message", "hello")));
    }
}
