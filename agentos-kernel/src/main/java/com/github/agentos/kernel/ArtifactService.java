package com.github.agentos.kernel;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行产物的存储与分发服务。
 *
 * <p>工具与 Agent 通过 {@link InvocationContext#artifacts()} 访问当前运行的
 * 产物存储，把文件类产出登记为可下载、可管理的产物，而不是在工具结果中
 * 传递本地路径。实现需保证内容与元数据在进程重启后仍可用。</p>
 */
public interface ArtifactService {

    /** Runner 未装配产物存储时的空实现：登记返回空、查询恒为空。 */
    ArtifactService NOOP = new ArtifactService() {
        @Override
        public Optional<Artifact> save(
                String sessionId, String invocationId,
                String filename, String contentType, byte[] bytes) {
            return Optional.empty();
        }

        @Override
        public Optional<ArtifactContent> load(String artifactId) {
            return Optional.empty();
        }

        @Override
        public List<Artifact> list(String sessionId) {
            return List.of();
        }

        @Override
        public boolean delete(String artifactId) {
            return false;
        }
    };

    /**
     * 登记一份产物内容并返回元数据。
     *
     * @param sessionId 产出产物的会话标识
     * @param invocationId 产出产物的 Invocation 标识，可为空
     * @param filename 原始文件名（不含路径）
     * @param contentType MIME 类型
     * @param bytes 原始内容字节
     * @return 登记成功时返回产物元数据；存储不可用时返回空
     */
    Optional<Artifact> save(
            String sessionId, String invocationId,
            String filename, String contentType, byte[] bytes);

    /** 按标识加载产物内容；不存在时返回空。 */
    Optional<ArtifactContent> load(String artifactId);

    /** 按标识读取产物元数据；用于在加载内容前完成访问控制。 */
    default Optional<Artifact> metadata(String artifactId) {
        return load(artifactId).map(ArtifactContent::artifact);
    }

    /** 列出指定会话的全部产物，按登记时间倒序。 */
    List<Artifact> list(String sessionId);

    /** 删除指定产物；不存在或删除失败时返回 {@code false}。 */
    boolean delete(String artifactId);
}
