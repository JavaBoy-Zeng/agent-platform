package com.github.agentos.memory;

/** 召回结果进入规划上下文前的硬限制。 */
public record MemoryRecallPolicy(
        int maxRecentTurns,
        int maxAtomicResults,
        int maxScenarios,
        int maxCharsPerMemory,
        int maxTotalChars,
        long timeoutMillis) {

    public MemoryRecallPolicy {
        if (maxRecentTurns < 0) throw new IllegalArgumentException("maxRecentTurns must not be negative");
        if (maxAtomicResults <= 0) throw new IllegalArgumentException("maxAtomicResults must be positive");
        if (maxScenarios < 0) throw new IllegalArgumentException("maxScenarios must not be negative");
        if (maxCharsPerMemory <= 0) throw new IllegalArgumentException("maxCharsPerMemory must be positive");
        if (maxTotalChars <= 0) throw new IllegalArgumentException("maxTotalChars must be positive");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
    }

    public static MemoryRecallPolicy defaults() {
        return new MemoryRecallPolicy(6, 5, 3, 1_200, 6_000, 2_000);
    }
}
