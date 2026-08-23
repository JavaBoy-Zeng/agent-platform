package com.github.agentos.memory;

/** 可替换的文本向量端口。 */
@FunctionalInterface
public interface MemoryEmbedding {

    double[] embed(String text);

    /** 用于区分持久化向量版本的稳定模型标识。 */
    default String modelId() {
        return getClass().getName();
    }
}
