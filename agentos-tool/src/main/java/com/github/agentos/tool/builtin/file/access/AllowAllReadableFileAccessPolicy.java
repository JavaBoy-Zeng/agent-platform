package com.github.agentos.tool.builtin.file.access;

import com.github.agentos.tool.api.ToolFailureType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 允许读取任意本机路径的开发期策略。
 *
 * <p>该策略不限定 workspace 根目录，只拒绝已经存在但不可读的路径。</p>
 */
public final class AllowAllReadableFileAccessPolicy implements FileAccessPolicy {

    @Override
    public Path authorizeRead(Path requestedPath) {
        Path normalized = Objects.requireNonNull(requestedPath, "requestedPath must not be null")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(normalized) && !Files.isReadable(normalized)) {
            throw new FileAccessException(
                    ToolFailureType.PERMISSION_DENIED, "path is not readable: " + normalized);
        }
        return normalized;
    }
}
