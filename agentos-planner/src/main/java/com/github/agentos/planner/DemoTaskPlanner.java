package com.github.agentos.planner;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.tool.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * 不调用大语言模型的演示任务规划器。
 *
 * <p>该实现只把用户输入转换为单个 {@code echo} 工具步骤，用于本地演示和端到端测试。
 * 服务端只会在 {@code demo} Spring Profile 下注册它，生产配置不应使用本实现。</p>
 */
public final class DemoTaskPlanner implements TaskPlanner {

    /**
     * 创建演示任务规划器。
     */
    public DemoTaskPlanner() {
    }

    /**
     * 将用户输入转换为单步骤回显计划。
     *
     * @param context 当前 Agent 运行上下文
     * @return 包含一个 {@code echo} 工具调用的演示计划
     */
    @Override
    public Plan createPlan(AgentContext context) {
        ToolCall call = new ToolCall("echo", Map.of("message", context.input()));
        Plan.Step step = new Plan.Step("step-1", "回显用户输入", call);
        return Plan.of(context.input(), List.of(step));
    }
}
