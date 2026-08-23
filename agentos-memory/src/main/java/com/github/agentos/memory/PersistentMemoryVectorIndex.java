package com.github.agentos.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 使用 {@link MemoryStore} 持久化文档向量的精确向量索引。
 *
 * <p>查询只计算一次 Query Embedding；文档向量在写入阶段生成并按内容版本复用。
 * 当前后端是可移植的精确余弦索引，后续可用 pgvector、Milvus 或 sqlite-vec 实现替换。</p>
 */
public final class PersistentMemoryVectorIndex implements MemoryVectorIndex {

    private final MemoryStore store;
    private final MemoryEmbedding embedding;

    public PersistentMemoryVectorIndex(MemoryStore store, MemoryEmbedding embedding) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.embedding = Objects.requireNonNull(embedding, "embedding must not be null");
    }

    @Override
    public void index(AtomicMemory memory) {
        Objects.requireNonNull(memory, "memory must not be null");
        MemoryVector current = store.findVector(memory.id(), embedding.modelId()).orElse(null);
        if (current != null && current.contentVersion() >= memory.version()) return;
        store.saveVector(new MemoryVector(
                memory.id(), embedding.modelId(), memory.version(),
                embedding.embed(memory.content()), Instant.now()));
    }

    @Override
    public List<Match> search(String queryText, List<AtomicMemory> candidates, int limit) {
        if (candidates.isEmpty() || limit <= 0) return List.of();
        double[] query = embedding.embed(queryText);
        List<Match> matches = new ArrayList<>();
        for (AtomicMemory memory : candidates) {
            MemoryVector vector = store.findVector(memory.id(), embedding.modelId()).orElse(null);
            if (vector == null || vector.contentVersion() != memory.version()) {
                index(memory);
                vector = store.findVector(memory.id(), embedding.modelId()).orElseThrow();
            }
            double score = cosine(query, vector.values());
            if (score > 0) matches.add(new Match(memory, score));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed());
        return matches.stream().limit(limit).toList();
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("embedding dimensions do not match");
        }
        double dot = 0;
        double a = 0;
        double b = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            a += left[index] * left[index];
            b += right[index] * right[index];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }
}
