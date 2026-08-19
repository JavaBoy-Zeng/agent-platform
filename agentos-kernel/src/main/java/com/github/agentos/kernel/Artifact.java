package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Agent 运行产出的非文本内容物的元数据。
 *
 * <p>Agent 的产出不只是字符串答案：报告、表格、图片、代码文件等都应当作为
 * 产物登记进 {@link ArtifactService}，由 Runtime 统一存储与分发，
 * 而不是把本地文件路径塞进工具结果。</p>
 *
 * @param artifactId 产物标识，由存储侧生成
 * @param sessionId 产出该产物的会话标识
 * @param invocationId 产出该产物的 Invocation 标识，未进入 Runner 时为空字符串
 * @param filename 原始文件名（不含路径）
 * @param contentType MIME 类型
 * @param sizeBytes 内容字节数
 * @param createdAt 登记时间
 */
public record Artifact(
        String artifactId,
        String sessionId,
        String invocationId,
        String filename,
        String contentType,
        long sizeBytes,
        Instant createdAt) {

    /** 校验并规范化产物元数据。 */
    public Artifact {
        artifactId = requireText(artifactId, "artifactId");
        sessionId = requireText(sessionId, "sessionId");
        invocationId = invocationId == null ? "" : invocationId.trim();
        filename = requireText(filename, "filename");
        contentType = requireText(contentType, "contentType");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
