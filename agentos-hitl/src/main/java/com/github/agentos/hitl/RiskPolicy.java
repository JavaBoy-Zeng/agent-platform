package com.github.agentos.hitl;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;

import java.util.Objects;

/**
 * 判断工具调用是否需要人工审批的风险策略。
 *
 * <p>当工具声明的风险等级大于或等于审批阈值时，该调用必须先通过人工审批。
 * 可继承扩展为内容级策略（如按命令文本判定风险）。</p>
 */
public class RiskPolicy {

    private final AgentTool.RiskLevel approvalThreshold;

    /**
     * 创建风险策略。
     *
     * @param approvalThreshold 触发人工审批的最低风险等级
     * @throws NullPointerException 当审批阈值为 {@code null} 时抛出
     */
    public RiskPolicy(AgentTool.RiskLevel approvalThreshold) {
        this.approvalThreshold = Objects.requireNonNull(
                approvalThreshold, "approvalThreshold must not be null");
    }

    /**
     * 判断指定工具调用是否需要人工审批。
     *
     * @param context 当前 Agent 运行上下文
     * @param tool 即将执行的工具
     * @param call 具体工具调用
     * @return 需要人工审批时返回 {@code true}
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     */
    public boolean requiresApproval(InvocationContext context, AgentTool tool, ToolCall call) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(call, "call must not be null");
        return tool.riskLevel().ordinal() >= approvalThreshold.ordinal();
    }
}
