package com.github.agentos.memory;

import java.util.List;

/**
 * 基于特征哈希的本地向量实现。
 *
 * <p>它用于零配置和测试环境，不声称具备语义模型能力；生产环境可替换为真实 Embedding。</p>
 */
public final class HashingMemoryEmbedding implements MemoryEmbedding {

    private final int dimensions;

    public HashingMemoryEmbedding() {
        this(256);
    }

    public HashingMemoryEmbedding(int dimensions) {
        if (dimensions < 32) throw new IllegalArgumentException("dimensions must be at least 32");
        this.dimensions = dimensions;
    }

    @Override
    public double[] embed(String text) {
        double[] vector = new double[dimensions];
        List<String> tokens = TextAnalyzer.tokenize(text);
        for (String token : tokens) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, dimensions);
            vector[index] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        normalize(vector);
        return vector;
    }

    private static void normalize(double[] vector) {
        double sum = 0;
        for (double value : vector) sum += value * value;
        if (sum == 0) return;
        double norm = Math.sqrt(sum);
        for (int index = 0; index < vector.length; index++) vector[index] /= norm;
    }

    @Override
    public String modelId() {
        return "agentos-hashing-v1";
    }
}
