package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Objects;

/**
 * Agent 会话的不可变状态快照。
 *
 * @param status    当前运行状态
 * @param iteration 已执行的迭代次数
 * @param output    成功执行后的输出
 * @param error     执行失败后的错误信息
 * @param updatedAt 状态最后更新时间
 */
public record AgentState(
        Status status,
        int iteration,
        String output,
        String error,
        Instant updatedAt) {

    /**
     * 创建并校验状态快照。
     *
     * @throws IllegalArgumentException 当迭代次数小于零时抛出
     * @throws NullPointerException     当状态或更新时间为 {@code null} 时抛出
     */
    public AgentState {
        status = Objects.requireNonNull(status, "status must not be null");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
        output = output == null ? "" : output;
        error = error == null ? "" : error;
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * 创建尚未运行的初始状态。
     *
     * @return 迭代次数为零的就绪状态
     */
    public static AgentState ready() {
        return new AgentState(Status.READY, 0, "", "", Instant.now());
    }

    /**
     * 基于当前状态开启下一次迭代。
     *
     * @return 迭代次数加一后的运行中状态
     */
    public AgentState startNextIteration() {
        return new AgentState(Status.RUNNING, iteration + 1, "", "", Instant.now());
    }

    /**
     * 将当前状态转换为执行完成状态。
     *
     * @param result Agent 的最终输出
     * @return 执行完成状态
     */
    public AgentState complete(String result) {
        return new AgentState(Status.COMPLETED, iteration, result, "", Instant.now());
    }

    /**
     * 将当前状态转换为执行失败状态。
     *
     * @param message 失败原因
     * @return 执行失败状态
     */
    public AgentState fail(String message) {
        return new AgentState(Status.FAILED, iteration, "", message, Instant.now());
    }

    /** 将当前状态转换为用户取消状态。 */
    public AgentState cancel(String message) {
        return new AgentState(Status.CANCELLED, iteration, "", message, Instant.now());
    }

    /**
     * Agent 会话支持的生命周期状态。
     */
    public enum Status {
        /**
         * 已创建，等待运行。
         */
        READY,
        /**
         * 正在执行 Agent 循环。
         */
        RUNNING,
        /**
         * 高风险操作正在等待人工审批。
         */
        WAITING_APPROVAL,
        /**
         * 本次运行已成功完成。
         */
        COMPLETED,
        /**
         * 本次运行执行失败。
         */
        FAILED,
        /**
         * 本次运行已被取消。
         */
        CANCELLED
    }
}
