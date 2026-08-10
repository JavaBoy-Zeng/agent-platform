package com.github.agentos.agent;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.planner.Plan;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.TaskPlanner;

import java.util.Objects;
import java.util.List;

/**
 * AgentOS 默认的主 Agent 编排循环。
 *
 * <p>主 Agent 按照“记录用户消息、创建计划、执行计划、记录 Agent 回复”的顺序完成
 * 一次运行，并将计划执行结果转换为内核状态。</p>
 */
public final class MainAgent implements AgentLoop {

    private final TaskPlanner taskPlanner;
    private final PlanExecutor planExecutor;
    private final MemoryService memoryService;

    /**
     * 创建主 Agent。
     *
     * @param taskPlanner 任务规划器
     * @param planExecutor 计划执行器
     * @param memoryService 记忆服务
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     */
    public MainAgent(
            TaskPlanner taskPlanner,
            PlanExecutor planExecutor,
            MemoryService memoryService) {
        this.taskPlanner = Objects.requireNonNull(taskPlanner, "taskPlanner must not be null");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
    }

    /**
     * 执行一次主 Agent 循环。
     *
     * @param context 当前运行上下文
     * @param runningState 已进入运行中的状态快照
     * @return 计划全部完成时返回完成状态，否则返回失败状态
     */
    @Override
    public AgentState run(AgentContext context, AgentState runningState) {
        Plan plan = taskPlanner.createPlan(context);
        PlanExecutor.ExecutionResult execution = planExecutor.execute(context, plan);
        String output = execution.output();

        if (!execution.completed()) {
            return runningState.fail(output.isBlank() ? "plan execution failed" : output);
        }

        MemoryScope scope = new MemoryScope(
                context.teamId(),
                context.userId(),
                context.agentId(),
                context.sessionId(),
                context.taskId());
        List<String> toolOutputs = execution.steps().stream()
                .map(step -> step.output().isBlank() ? step.error() : step.output())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        try {
            memoryService.capture(CompletedTurn.success(scope, context.input(), output, toolOutputs));
        } catch (RuntimeException ignored) {
            // Memory is fail-open: persistence must not turn a successful agent run into a failure.
        }
        return runningState.complete(output);
    }
}
