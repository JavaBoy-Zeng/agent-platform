package com.github.agentos.hitl;

import com.github.agentos.tool.runtime.ToolBeforeResult;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.runtime.ToolInterceptor;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionType;

import java.util.Objects;

/** 将风险判断和可恢复人工审批封装为工具前置生命周期拦截器。 */
public final class ApprovalToolInterceptor implements ToolInterceptor {

    private final RiskPolicy riskPolicy;
    private final ApprovalService approvalService;

    /** 创建审批拦截器。 */
    public ApprovalToolInterceptor(RiskPolicy riskPolicy, ApprovalService approvalService) {
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
        this.approvalService = Objects.requireNonNull(
                approvalService, "approvalService must not be null");
    }

    /** 高风险调用未获批准时返回挂起动作，恢复后允许真实工具执行。 */
    @Override
    public ToolBeforeResult beforeExecute(ToolCall call, ToolContext context) {
        String approvalMode = String.valueOf(
                context.request().attributes().getOrDefault("approvalMode", "RISK_BASED"));
        boolean requiresApproval = switch (approvalMode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FULL_ACCESS" -> false;
            case "REQUEST_APPROVAL" -> requiresExplicitApproval(context.tool().name())
                    || riskPolicy.requiresApproval(context.invocation(), context.tool(), call);
            default -> riskPolicy.requiresApproval(context.invocation(), context.tool(), call);
        };
        if (!requiresApproval) {
            return ToolBeforeResult.allow();
        }
        if (context.invocation().invocation() != null
                && context.invocation().invocation().consumeApproval(
                        context.tool().name(), call.arguments())) {
            return ToolBeforeResult.allow();
        }
        PendingAction action = new PendingAction(
                java.util.UUID.randomUUID().toString(),
                PendingActionType.HUMAN_APPROVAL,
                "批准工具调用 " + context.tool().name(),
                context.tool().description(),
                java.util.Map.of(
                        "toolName", context.tool().name(),
                        "arguments", call.arguments(),
                        "riskLevel", context.tool().riskLevel().name()));
        return ToolBeforeResult.shortCircuit(ToolResult.pending(action));
    }

    private static boolean requiresExplicitApproval(String toolName) {
        String normalized = toolName == null
                ? "" : toolName.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("file_write")
                || normalized.equals("run_command")
                || normalized.equals("weather")
                || normalized.contains("browser")
                || normalized.contains("search")
                || normalized.contains("http")
                || normalized.contains("web");
    }
}
