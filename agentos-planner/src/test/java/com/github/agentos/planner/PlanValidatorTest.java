package com.github.agentos.planner;

import com.github.agentos.tool.EchoTool;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证模型计划执行前的工具和参数边界。
 */
class PlanValidatorTest {

    private final ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));

    /**
     * 验证不存在的工具不能进入执行阶段。
     */
    @Test
    void rejectsUnknownTool() {
        Plan plan = Plan.of("测试未知工具", List.of(new Plan.Step(
                "step-1",
                "调用未知工具",
                new ToolCall("missing", Map.of()))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("unknown tool missing");
    }

    /**
     * 验证缺失必填参数的工具调用会被拒绝。
     */
    @Test
    void rejectsMissingRequiredArgument() {
        Plan plan = Plan.of("测试必填参数", List.of(new Plan.Step(
                "step-1",
                "缺少消息",
                new ToolCall("echo", Map.of()))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("misses required argument message");
    }

    /**
     * 验证参数类型错误和未声明参数都会被报告。
     */
    @Test
    void rejectsWrongTypeAndUnknownArgument() {
        Plan plan = Plan.of("测试参数结构", List.of(new Plan.Step(
                "step-1",
                "错误参数",
                new ToolCall("echo", Map.of("message", 42, "extra", true)))));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("argument message must be STRING")
                .hasMessageContaining("unknown argument extra");
    }

    /**
     * 验证超过配置上限的计划不会进入执行阶段。
     */
    @Test
    void rejectsPlanAboveStepLimit() {
        Plan plan = Plan.of("测试步骤上限", List.of(
                echoStep("step-1"),
                echoStep("step-2")));

        assertThatThrownBy(() -> new PlanValidator(toolRegistry, 1).validate(plan))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("step count 2 exceeds limit 1");
    }

    private static Plan.Step echoStep(String id) {
        return new Plan.Step(id, "回显消息", new ToolCall("echo", Map.of("message", "hello")));
    }
}
