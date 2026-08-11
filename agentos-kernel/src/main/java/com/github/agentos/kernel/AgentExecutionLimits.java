package com.github.agentos.kernel;

/**
 * 单次 Agent 运行的累计资源预算。
 *
 * @param maxReplanCount 最大重规划次数
 * @param maxStepCount 最大累计处理步骤数
 * @param maxToolCalls 最大累计工具调用次数
 * @param maxModelCalls 最大累计模型调用次数
 */
public record AgentExecutionLimits(
        int maxReplanCount,
        int maxStepCount,
        int maxToolCalls,
        int maxModelCalls) {

    /** 创建并校验运行预算。 */
    public AgentExecutionLimits {
        if (maxReplanCount < 0) {
            throw new IllegalArgumentException("maxReplanCount must not be negative");
        }
        requirePositive(maxStepCount, "maxStepCount");
        requirePositive(maxToolCalls, "maxToolCalls");
        requirePositive(maxModelCalls, "maxModelCalls");
    }

    /** 返回适合一般任务和代码侦察的默认预算。 */
    public static AgentExecutionLimits defaults() {
        return new AgentExecutionLimits(3, 30, 30, 6);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
