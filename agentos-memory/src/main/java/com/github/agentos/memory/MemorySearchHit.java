package com.github.agentos.memory;

import java.util.Objects;

/** 一条带融合排序分数的 L1 召回结果。 */
public record MemorySearchHit(AtomicMemory memory, double score, String strategy) {

    public MemorySearchHit {
        memory = Objects.requireNonNull(memory, "memory must not be null");
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("strategy must not be blank");
        }
    }
}
