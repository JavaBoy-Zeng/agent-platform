package com.github.agentos.kernel;

import java.util.Arrays;
import java.util.Objects;

/**
 * 产物内容：元数据与原始字节的组合，供下载链路一次性取回。
 *
 * @param artifact 产物元数据
 * @param bytes 原始内容字节
 */
public record ArtifactContent(Artifact artifact, byte[] bytes) {

    /** 校验内容非空。 */
    public ArtifactContent {
        Objects.requireNonNull(artifact, "artifact must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");
    }

    /** 返回内容字节数组副本，避免调用方修改内部状态。 */
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArtifactContent content
                && artifact.equals(content.artifact)
                && Arrays.equals(bytes, content.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * artifact.hashCode() + Arrays.hashCode(bytes);
    }
}
