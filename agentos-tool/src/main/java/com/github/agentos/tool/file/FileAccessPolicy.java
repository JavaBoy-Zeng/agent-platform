package com.github.agentos.tool.file;

import java.nio.file.Path;

/**
 * 文件工具访问路径前的授权边界。
 *
 * <p>当前实现可以允许读取整个本机文件系统；未来可替换为 workspace sandbox，
 * 而无需修改具体文件工具。</p>
 */
public interface FileAccessPolicy {

    /**
     * 规范化并授权一个待读取路径。
     *
     * @param requestedPath 调用方提供的路径
     * @return 允许访问的规范化绝对路径
     * @throws FileAccessException 当策略或文件系统拒绝访问时抛出
     */
    Path authorizeRead(Path requestedPath);
}
