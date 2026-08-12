package com.github.agentos.tool.file;

import com.github.agentos.tool.ToolFailureType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 允许访问任意本机路径的开发期文件策略。
 *
 * <p>该策略不提供工作区隔离，只适合本地开发。生产环境应替换为限定允许根目录的策略。</p>
 */
public final class AllowAllFileAccessPolicy implements FileAccessPolicy {

    @Override
    public Path authorizeRead(Path requestedPath) {
        Path normalized = normalize(requestedPath);
        if (Files.exists(normalized) && !Files.isReadable(normalized)) {
            throw new FileAccessException(
                    ToolFailureType.PERMISSION_DENIED, "path is not readable: " + normalized);
        }
        return normalized;
    }

    @Override
    public Path authorizeWrite(Path requestedPath) {
        Path normalized = normalize(requestedPath);
        if (Files.exists(normalized)) {
            if (Files.isDirectory(normalized)) {
                throw new FileAccessException(
                        ToolFailureType.INVALID_ARGUMENT,
                        "write target is a directory: " + normalized);
            }
            if (!Files.isWritable(normalized)) {
                throw new FileAccessException(
                        ToolFailureType.PERMISSION_DENIED,
                        "path is not writable: " + normalized);
            }
            return normalized;
        }

        Path ancestor = normalized.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null || !Files.isDirectory(ancestor) || !Files.isWritable(ancestor)) {
            throw new FileAccessException(
                    ToolFailureType.PERMISSION_DENIED,
                    "parent path is not writable: " + normalized);
        }
        return normalized;
    }

    private static Path normalize(Path requestedPath) {
        return Objects.requireNonNull(requestedPath, "requestedPath must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
