package com.github.agentos.tool.builtin.file.access;

import com.github.agentos.tool.api.ToolFailureType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 将所有文件读写限制在一个固定根目录内的访问策略。
 *
 * <p>相对路径始终相对于允许根目录解析；绝对路径、{@code ..} 以及符号链接最终指向
 * 根目录外时都会被拒绝。对于尚不存在的写入目标，会校验其最近的已存在祖先，避免
 * 通过目录符号链接逃逸。</p>
 */
public final class RootedFileAccessPolicy implements FileAccessPolicy {

    private final Path root;

    /**
     * 创建根目录隔离策略。
     *
     * @param allowedRoot 唯一允许访问的目录
     */
    public RootedFileAccessPolicy(Path allowedRoot) {
        Path normalized = Objects.requireNonNull(allowedRoot, "allowedRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(
                    "file access root is not a directory: " + normalized);
        }
        try {
            this.root = normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "cannot resolve file access root: " + normalized, exception);
        }
    }

    /** 返回已经解析符号链接的允许根目录。 */
    public Path root() {
        return root;
    }

    @Override
    public Optional<Path> allowedRoot() {
        return Optional.of(root);
    }

    @Override
    public Path authorizeRead(Path requestedPath) {
        Path candidate = resolveInsideRoot(requestedPath);
        Path authorized = resolveExistingTarget(candidate);
        if (Files.exists(authorized) && !Files.isReadable(authorized)) {
            throw new FileAccessException(
                    ToolFailureType.PERMISSION_DENIED, "path is not readable: " + authorized);
        }
        return authorized;
    }

    @Override
    public Path authorizeWrite(Path requestedPath) {
        Path candidate = resolveInsideRoot(requestedPath);
        Path authorized = resolveExistingTarget(candidate);
        if (Files.exists(authorized)) {
            if (Files.isDirectory(authorized)) {
                throw new FileAccessException(
                        ToolFailureType.INVALID_ARGUMENT,
                        "write target is a directory: " + authorized);
            }
            if (!Files.isWritable(authorized)) {
                throw new FileAccessException(
                        ToolFailureType.PERMISSION_DENIED,
                        "path is not writable: " + authorized);
            }
            return authorized;
        }

        Path ancestor = nearestExistingAncestor(candidate);
        if (!Files.isDirectory(ancestor) || !Files.isWritable(ancestor)) {
            throw new FileAccessException(
                    ToolFailureType.PERMISSION_DENIED,
                    "parent path is not writable: " + candidate);
        }
        return candidate;
    }

    private Path resolveInsideRoot(Path requestedPath) {
        Path requested = Objects.requireNonNull(
                requestedPath, "requestedPath must not be null");
        Path lexicalCandidate = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        Path candidate = canonicalizeFromExistingAncestor(lexicalCandidate);
        requireInsideRoot(candidate);
        return candidate;
    }

    private Path canonicalizeFromExistingAncestor(Path candidate) {
        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw denied("path has no accessible parent: " + candidate);
        }
        try {
            Path realAncestor = ancestor.toRealPath();
            return realAncestor.resolve(ancestor.relativize(candidate)).normalize();
        } catch (IOException exception) {
            throw denied("cannot resolve path: " + candidate);
        }
    }

    private Path resolveExistingTarget(Path candidate) {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = candidate.toRealPath();
                requireInsideRoot(real);
                return real;
            } catch (IOException exception) {
                throw denied("cannot resolve path: " + candidate);
            }
        }
        nearestExistingAncestor(candidate);
        return candidate;
    }

    private Path nearestExistingAncestor(Path candidate) {
        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw denied("path has no accessible parent: " + candidate);
        }
        try {
            requireInsideRoot(ancestor.toRealPath());
        } catch (IOException exception) {
            throw denied("cannot resolve parent path: " + ancestor);
        }
        return ancestor;
    }

    private void requireInsideRoot(Path candidate) {
        if (!candidate.startsWith(root)) {
            throw denied("path is outside the allowed root " + root + ": " + candidate);
        }
    }

    private static FileAccessException denied(String message) {
        return new FileAccessException(ToolFailureType.SECURITY_DENIED, message);
    }
}
