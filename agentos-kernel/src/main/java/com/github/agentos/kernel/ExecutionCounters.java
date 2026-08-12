package com.github.agentos.kernel;

/** Checkpoint 中保存的累计执行计数器。 */
public record ExecutionCounters(int modelCalls, int toolCalls, int replans, int steps) {
    /** 校验计数器均为非负数。 */
    public ExecutionCounters {
        if (modelCalls < 0 || toolCalls < 0 || replans < 0 || steps < 0) {
            throw new IllegalArgumentException("execution counters must not be negative");
        }
    }
}
