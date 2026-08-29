package com.github.agentos.agent.loop;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.StepResult;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.tool.api.ToolCall;

import java.util.List;
import java.util.Optional;

/**
 * 主 Agent 断点续跑状态的持久化协议。
 *
 * <p>WAITING 状态的运行会把剩余计划与累计结果写入存储；进程重启后
 * 审批恢复可以从存储读回续跑状态。内存实现之外可提供 SQLite 等持久化实现。</p>
 *
 * <p>该 record 同时承载两类续跑状态：MainAgent 的剩余计划 +
 * 累计结果（remainingPlan/cumulativeResults/processedSteps），
 * 以及 ReactAgent 的对话消息序列 + 挂起的工具调用
 * （reactMessages/pendingToolCall/pendingToolCallId/pendingToolName）。
 * 两种状态字段互不读取，开发阶段无历史数据兼容问题。</p>
 */
public interface ContinuationStore {

    /** 保存或覆盖指定 Invocation 的续跑状态。 */
    void save(String invocationId, PersistedContinuation continuation);

    /** 读取指定 Invocation 的续跑状态。 */
    Optional<PersistedContinuation> load(String invocationId);

    /** 删除指定 Invocation 的续跑状态。 */
    void delete(String invocationId);

    /** 不做任何持久化的空实现。 */
    ContinuationStore NOOP = new ContinuationStore() {
        @Override
        public void save(String invocationId, PersistedContinuation continuation) {
        }

        @Override
        public Optional<PersistedContinuation> load(String invocationId) {
            return Optional.empty();
        }

        @Override
        public void delete(String invocationId) {
        }
    };

    /**
     * 可安全跨进程序列化的续跑状态。
     *
     * <p>MainAgent 专用字段：remainingPlan、cumulativeResults、modelCalls、
     * replanCount、processedSteps、toolCalls。</p>
     *
     * <p>ReactAgent 专用字段：reactMessages（已累积的对话消息序列）、
     * pendingToolCall（挂起时未完成工具调用的参数）、pendingToolCallId
     * （该调用在原生协议中与 TOOL 结果配对的 id）、pendingToolName。</p>
     *
     * @param request 原始用户请求
     * @param remainingPlan 从挂起步骤开始的剩余计划（MainAgent 专用）
     * @param cumulativeResults 已完成步骤的累计结果（MainAgent 专用）
     * @param modelCalls 已消耗模型调用数
     * @param replanCount 已发生重规划次数（MainAgent 专用）
     * @param processedSteps 已处理步骤数（MainAgent 专用）
     * @param toolCalls 已消耗工具调用数
     * @param reactMessages 已累积的对话消息序列（ReactAgent 专用，包含 user/assistant/TOOL）
     * @param pendingToolCall 挂起时未完成工具调用的参数（ReactAgent 专用）
     * @param pendingToolCallId 该调用在原生协议中与 TOOL 结果配对的 id（ReactAgent 专用）
     * @param pendingToolName 挂起工具调用的工具名（ReactAgent 专用）
     */
    record PersistedContinuation(
            AgentRequest request,
            AgentPlan remainingPlan,
            List<StepResult> cumulativeResults,
            int modelCalls,
            int replanCount,
            int processedSteps,
            int toolCalls,
            List<LlmMessage> reactMessages,
            ToolCall pendingToolCall,
            String pendingToolCallId,
            String pendingToolName) {

        /** 复制集合并校验。 */
        public PersistedContinuation {
            java.util.Objects.requireNonNull(request, "request must not be null");
            // MainAgent 字段可空（ReactAgent 不用）
            remainingPlan = remainingPlan;  // 保持 null 安全
            cumulativeResults = cumulativeResults == null
                    ? List.of() : List.copyOf(cumulativeResults);
            reactMessages = reactMessages == null
                    ? List.of() : List.copyOf(reactMessages);
        }

        /** 创建 MainAgent 用的续跑状态（向后兼容的便捷构造）。 */
        public PersistedContinuation(
                AgentRequest request,
                AgentPlan remainingPlan,
                List<StepResult> cumulativeResults,
                int modelCalls,
                int replanCount,
                int processedSteps,
                int toolCalls) {
            this(request, remainingPlan, cumulativeResults, modelCalls, replanCount,
                    processedSteps, toolCalls, List.of(), null, null, null);
        }

        /** 创建 ReactAgent 用的续跑状态。 */
        public static PersistedContinuation forReact(
                AgentRequest request,
                List<LlmMessage> reactMessages,
                int modelCalls,
                int toolCalls,
                ToolCall pendingToolCall,
                String pendingToolCallId,
                String pendingToolName) {
            return new PersistedContinuation(
                    request, null, List.of(), modelCalls, 0, 0, toolCalls,
                    reactMessages, pendingToolCall, pendingToolCallId, pendingToolName);
        }
    }
}
