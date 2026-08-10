package com.github.agentos.planner;

import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentContext;
import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolExecutor;
import com.github.agentos.tool.ToolRegistry;
import com.github.agentos.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 按顺序执行计划步骤的执行器。
 *
 * <p>每个步骤依次经过工具解析、风险判断、必要的人工审批和工具执行。
 * 任一步骤失败或被拒绝后，执行器会立即停止后续步骤。</p>
 */
public final class PlanExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final RiskPolicy riskPolicy;
    private final ApprovalService approvalService;

    /**
     * 创建计划执行器。
     *
     * @param toolRegistry 工具注册表
     * @param toolExecutor 工具执行器
     * @param riskPolicy 风险判断策略
     * @param approvalService 人工审批服务
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     */
    public PlanExecutor(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            RiskPolicy riskPolicy,
            ApprovalService approvalService) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
        this.approvalService = Objects.requireNonNull(approvalService, "approvalService must not be null");
    }

    /**
     * 在指定 Agent 上下文中执行计划。
     *
     * @param context 当前 Agent 运行上下文
     * @param plan 待执行计划
     * @return 包含各步骤结果的执行汇总
     * @throws NullPointerException 当上下文或计划为 {@code null} 时抛出
     */
    public ExecutionResult execute(AgentContext context, Plan plan) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        List<StepResult> results = new ArrayList<>();

        for (Plan.Step step : plan.steps()) {
            AgentTool tool;
            try {
                tool = toolRegistry.require(step.toolCall().toolName());
            } catch (IllegalArgumentException exception) {
                results.add(StepResult.failed(step.id(), exception.getMessage()));
                break;
            }

            if (riskPolicy.requiresApproval(context, tool, step.toolCall())
                    && !approvalService.requestApproval(context, tool, step.toolCall())) {
                results.add(StepResult.rejected(step.id(), "human approval was not granted"));
                break;
            }

            ToolResult toolResult = toolExecutor.execute(step.toolCall());
            results.add(toolResult.success()
                    ? StepResult.completed(step.id(), toolResult.output())
                    : StepResult.failed(step.id(), toolResult.error()));
            if (!toolResult.success()) {
                break;
            }
        }

        boolean completed = results.size() == plan.steps().size()
                && results.stream().allMatch(result -> result.status() == StepStatus.COMPLETED);
        return new ExecutionResult(plan.id(), completed, results);
    }

    /**
     * 单个计划步骤的执行状态。
     */
    public enum StepStatus {
        /** 步骤已经成功完成。 */
        COMPLETED,
        /** 步骤查找或执行失败。 */
        FAILED,
        /** 步骤未通过人工审批。 */
        REJECTED
    }

    /**
     * 单个计划步骤的执行结果。
     *
     * @param stepId 步骤标识
     * @param status 步骤执行状态
     * @param output 成功时的输出
     * @param error 失败或拒绝时的错误信息
     */
    public record StepResult(String stepId, StepStatus status, String output, String error) {

        /**
         * 创建步骤成功结果。
         *
         * @param stepId 步骤标识
         * @param output 步骤输出
         * @return 成功结果
         */
        public static StepResult completed(String stepId, String output) {
            return new StepResult(stepId, StepStatus.COMPLETED, output, "");
        }

        /**
         * 创建步骤失败结果。
         *
         * @param stepId 步骤标识
         * @param error 失败原因
         * @return 失败结果
         */
        public static StepResult failed(String stepId, String error) {
            return new StepResult(stepId, StepStatus.FAILED, "", error);
        }

        /**
         * 创建步骤审批拒绝结果。
         *
         * @param stepId 步骤标识
         * @param error 拒绝原因
         * @return 审批拒绝结果
         */
        public static StepResult rejected(String stepId, String error) {
            return new StepResult(stepId, StepStatus.REJECTED, "", error);
        }
    }

    /**
     * 一份计划的执行汇总。
     *
     * @param planId 计划标识
     * @param completed 是否所有计划步骤都成功完成
     * @param steps 已执行步骤的结果列表
     */
    public record ExecutionResult(String planId, boolean completed, List<StepResult> steps) {

        /**
         * 创建执行汇总，并复制步骤结果列表以保证不可变性。
         *
         * @throws NullPointerException 当步骤结果列表为 {@code null} 时抛出
         */
        public ExecutionResult {
            steps = List.copyOf(steps);
        }

        /**
         * 将各步骤的输出或错误信息按行合并。
         *
         * @return 适合写入 Agent 最终状态的汇总文本
         */
        public String output() {
            return steps.stream()
                    .map(step -> step.output().isBlank() ? step.error() : step.output())
                    .collect(Collectors.joining(System.lineSeparator()));
        }
    }
}
