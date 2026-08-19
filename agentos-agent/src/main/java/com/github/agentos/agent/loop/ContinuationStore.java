package com.github.agentos.agent.loop;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.StepResult;

import java.util.List;
import java.util.Optional;

/**
 * 主 Agent 断点续跑状态的持久化协议。
 *
 * <p>WAITING 状态的运行会把剩余计划与累计结果写入存储；进程重启后
 * 审批恢复可以从存储读回续跑状态。内存实现之外可提供 SQLite 等持久化实现。</p>
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
     * @param request 原始用户请求
     * @param remainingPlan 从挂起步骤开始的剩余计划
     * @param cumulativeResults 已完成步骤的累计结果
     * @param modelCalls 已消耗模型调用数
     * @param replanCount 已发生重规划次数
     * @param processedSteps 已处理步骤数
     * @param toolCalls 已消耗工具调用数
     */
    record PersistedContinuation(
            AgentRequest request,
            AgentPlan remainingPlan,
            List<StepResult> cumulativeResults,
            int modelCalls,
            int replanCount,
            int processedSteps,
            int toolCalls) {

        /** 复制集合并校验。 */
        public PersistedContinuation {
            java.util.Objects.requireNonNull(request, "request must not be null");
            java.util.Objects.requireNonNull(remainingPlan, "remainingPlan must not be null");
            cumulativeResults = List.copyOf(cumulativeResults);
        }
    }
}
