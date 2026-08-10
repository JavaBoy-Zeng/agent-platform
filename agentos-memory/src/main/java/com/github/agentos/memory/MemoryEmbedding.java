package com.github.agentos.memory;

/** 可替换的文本向量端口。 */
@FunctionalInterface
public interface MemoryEmbedding {

    double[] embed(String text);
}
