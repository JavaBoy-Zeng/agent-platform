package com.github.agentos.tool.builtin.file.access;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件工具访问路径前的授权边界。
 *
 * <p>当前实现可以允许读取整个本机文件系统；未来可替换为 workspace sandbox，
 * 而无需修改具体文件工具。</p>
 */
public interface FileAccessPolicy {

    /**
     * 返回可安全公开给规划器的固定访问根目录。
     *
     * <p>没有固定根目录的策略返回空值。规划器只能把该值用于提前判断明显越界的路径；
     * 最终授权仍必须由 {@link #authorizeRead(Path)} 或 {@link #authorizeWrite(Path)} 完成。</p>
     *
     * @return 固定允许根目录；策略没有单一根目录时为空
     */
    default Optional<Path> allowedRoot() {
        return Optional.empty();
    }

    /**
     * 规范化并授权一个待读取路径。
     *
     * @param requestedPath 调用方提供的路径
     * @return 允许访问的规范化绝对路径
     * @throws FileAccessException 当策略或文件系统拒绝访问时抛出
     */
    Path authorizeRead(Path requestedPath);

    /**
     * 规范化并判断一个路径能否出现在目录枚举或搜索结果中。
     *
     * <p>返回空值表示该路径对 Agent 不可发现：目录工具不得展示其名称，搜索工具
     * 不得匹配其名称或读取其内容。默认实现与普通读取授权一致；需要隐藏敏感文件的
     * 策略应覆盖此方法。</p>
     *
     * @param requestedPath 文件系统遍历发现的路径
     * @return 允许发现的规范化绝对路径；不可发现时为空
     * @throws FileAccessException 当路径本身越过访问边界时抛出
     */
    default Optional<Path> authorizeDiscovery(Path requestedPath) {
        return Optional.of(authorizeRead(requestedPath));
    }

    /**
     * 规范化并授权一个待写入路径。
     *
     * <p>默认拒绝写入，避免已有的只读策略在新增写工具后意外获得写权限。
     * 允许写入的策略必须显式覆盖此方法。</p>
     *
     * @param requestedPath 调用方提供的目标文件路径
     * @return 允许写入的规范化绝对路径
     * @throws FileAccessException 当策略或文件系统拒绝写入时抛出
     */
    default Path authorizeWrite(Path requestedPath) {
        throw new FileAccessException(
                com.github.agentos.tool.api.ToolFailureType.SECURITY_DENIED,
                "file writes are not allowed by the active access policy");
    }
}
