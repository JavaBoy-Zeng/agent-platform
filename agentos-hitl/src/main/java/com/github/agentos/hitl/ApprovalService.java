package com.github.agentos.hitl;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;

import java.time.Instant;
import java.util.Objects;

/**
 * 获取人工审批决策的统一服务。
 *
 * <p>该服务将 Agent 上下文和工具调用转换为标准审批请求，再交给可替换的
 * {@link ApprovalHandler}。处理器可以对接审批页面、消息队列或外部工作流。</p>
 */
public final class ApprovalService {

    private final ApprovalHandler handler;

    /**
     * 创建人工审批服务。
     *
     * @param handler 实际处理审批请求的适配器
     * @throws NullPointerException 当处理器为 {@code null} 时抛出
     */
    public ApprovalService(ApprovalHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    /**
     * 为指定工具调用请求人工审批。
     *
     * @param agentRequest 当前 Agent 请求
     * @param context 当前 Agent 运行上下文
     * @param tool 即将执行的工具
     * @param call 具体工具调用
     * @return 审批通过时返回 {@code true}，否则返回 {@code false}
     */
    public boolean requestApproval(
            AgentRequest agentRequest, InvocationContext context, AgentTool tool, ToolCall call) {
        Objects.requireNonNull(agentRequest, "agentRequest must not be null");
        Objects.requireNonNull(context, "context must not be null");
        ApprovalRequest approvalRequest = new ApprovalRequest(
                agentRequest.sessionId(), context.agentId(), tool.name(), tool.description(), call, Instant.now());
        return handler.approve(approvalRequest);
    }

    /**
     * 对接具体人工审批渠道的函数式接口。
     */
    @FunctionalInterface
    public interface ApprovalHandler {
        /**
         * 处理一条审批请求。
         *
         * @param request 待审批请求
         * @return 批准执行时返回 {@code true}
         */
        boolean approve(ApprovalRequest request);
    }

    /**
     * 发送给人工审批渠道的不可变请求。
     *
     * @param sessionId 发起调用的会话标识
     * @param agentId 发起调用的 Agent 标识
     * @param toolName 工具名称
     * @param toolDescription 工具功能说明
     * @param call 完整工具调用
     * @param requestedAt 审批请求创建时间
     */
    public record ApprovalRequest(
            String sessionId,
            String agentId,
            String toolName,
            String toolDescription,
            ToolCall call,
            Instant requestedAt) {
    }
}
