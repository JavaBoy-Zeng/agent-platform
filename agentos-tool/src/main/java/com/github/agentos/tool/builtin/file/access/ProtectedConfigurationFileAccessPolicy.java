package com.github.agentos.tool.builtin.file.access;

import com.github.agentos.tool.api.ToolFailureType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 对 Agent 隐藏并禁止读取部署配置文件的访问策略装饰器。
 *
 * <p>受保护文件不会出现在目录枚举和文件搜索结果中，内容搜索也不会打开这些文件；
 * 直接读取或写入统一返回安全拒绝。底层策略仍负责根目录和符号链接边界，因此通过
 * 别名符号链接也无法绕过保护。</p>
 */
public final class ProtectedConfigurationFileAccessPolicy implements FileAccessPolicy {

    private static final Set<String> PROTECTED_EXACT_NAMES = Set.of(
            ".env",
            ".envrc",
            "caddyfile",
            "dockerfile");

    private static final Set<String> PROTECTED_SUFFIXES = Set.of(
            ".cfg",
            ".cnf",
            ".conf",
            ".config",
            ".crt",
            ".ini",
            ".json",
            ".key",
            ".p12",
            ".pem",
            ".pfx",
            ".plist",
            ".properties",
            ".toml",
            ".xml",
            ".yaml",
            ".yml");

    private final FileAccessPolicy delegate;

    public ProtectedConfigurationFileAccessPolicy(FileAccessPolicy delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public Optional<Path> allowedRoot() {
        return delegate.allowedRoot();
    }

    @Override
    public Path authorizeRead(Path requestedPath) {
        Path authorized = delegate.authorizeRead(requestedPath);
        requireNotProtected(authorized);
        return authorized;
    }

    @Override
    public Optional<Path> authorizeDiscovery(Path requestedPath) {
        return delegate.authorizeDiscovery(requestedPath)
                .filter(path -> !isProtectedConfiguration(path));
    }

    @Override
    public Path authorizeWrite(Path requestedPath) {
        Path authorized = delegate.authorizeWrite(requestedPath);
        requireNotProtected(authorized);
        return authorized;
    }

    static boolean isProtectedConfiguration(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toString().toLowerCase(Locale.ROOT);
        if (PROTECTED_EXACT_NAMES.contains(normalized)
                || normalized.startsWith(".env.")
                || normalized.startsWith("caddyfile.")
                || normalized.startsWith("caddyfile-")
                || normalized.startsWith("dockerfile.")
                || normalized.startsWith("dockerfile-")) {
            return true;
        }
        return PROTECTED_SUFFIXES.stream().anyMatch(suffix ->
                normalized.endsWith(suffix)
                        || normalized.contains(suffix + ".")
                        || normalized.contains(suffix + "-")
                        || normalized.endsWith(suffix + "~"));
    }

    private static void requireNotProtected(Path path) {
        if (isProtectedConfiguration(path)) {
            throw new FileAccessException(
                    ToolFailureType.SECURITY_DENIED,
                    "protected deployment configuration files are not accessible to the agent");
        }
    }
}
