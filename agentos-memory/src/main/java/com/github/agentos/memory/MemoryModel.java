package com.github.agentos.memory;

import java.util.List;

/**
 * L1 抽取、L2 场景归纳和 L3 画像生成的模型端口。
 *
 * <p>默认实现完全在本地运行；生产环境可以使用结构化 LLM 实现替换。</p>
 */
public interface MemoryModel {

    List<AtomicCandidate> extractAtomic(CompletedTurn turn);

    String synthesizeScenario(MemoryScope scope, List<AtomicMemory> memories);

    String synthesizeProfile(
            MemoryScope scope,
            List<AtomicMemory> memories,
            List<ScenarioMemory> scenarios);

    /** 尚未分配持久化标识的 L1 候选。 */
    record AtomicCandidate(
            MemoryType type,
            String content,
            double confidence,
            int priority) {

        public AtomicCandidate {
            if (type == null) throw new NullPointerException("type must not be null");
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            if (priority < 0 || priority > 10) {
                throw new IllegalArgumentException("priority must be between 0 and 10");
            }
            content = content.trim();
        }
    }
}
