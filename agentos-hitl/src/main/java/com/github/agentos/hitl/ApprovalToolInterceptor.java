package com.github.agentos.hitl;

import com.github.agentos.tool.ToolBeforeResult;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolExecutionContext;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolInterceptor;
import com.github.agentos.tool.ToolResult;

import java.util.Objects;

/** 将风险判断和同步人工审批封装为工具前置生命周期拦截器。 */
public final class ApprovalToolInterceptor implements ToolInterceptor {

    private final RiskPolicy riskPolicy;
    private final ApprovalService approvalService;

    /** 创建审批拦截器。 */
    public ApprovalToolInterceptor(RiskPolicy riskPolicy, ApprovalService approvalService) {
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy must not be null");
        this.approvalService = Objects.requireNonNull(
                approvalService, "approvalService must not be null");
    }

    /** 高风险调用未获批准时在真实工具执行前短路。 */
    @Override
    public ToolBeforeResult beforeExecute(ToolCall call, ToolExecutionContext context) {
        if (!riskPolicy.requiresApproval(context.agentContext(), context.tool(), call)) {
            return ToolBeforeResult.allow();
        }
        boolean approved = approvalService.requestApproval(
                context.request(), context.agentContext(), context.tool(), call);
        return approved ? ToolBeforeResult.allow() : ToolBeforeResult.shortCircuit(
                ToolResult.failure(
                        ToolFailureType.SECURITY_DENIED, "human approval was not granted"));
    }
}
