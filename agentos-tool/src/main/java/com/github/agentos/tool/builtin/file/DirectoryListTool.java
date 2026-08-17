package com.github.agentos.tool.builtin.file;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;

import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 有界列出目录结构，帮助规划器先侦察未知工作区。 */
public final class DirectoryListTool implements AgentTool {

    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int DEFAULT_MAX_ENTRIES = 200;
    private final FileAccessPolicy accessPolicy;

    public DirectoryListTool(FileAccessPolicy accessPolicy) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    @Override
    public String name() {
        return "directory_list";
    }

    @Override
    public String description() {
        return "递归列出本地目录结构，不跟随符号链接，并跳过隐藏文件及隐藏目录；用于在读取文件前确认真实项目结构";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter("path", ToolParameter.ValueType.STRING, "要列出的目录路径", true),
                new ToolParameter("maxDepth", ToolParameter.ValueType.INTEGER, "递归深度，默认 2，范围 0-5", false),
                new ToolParameter("maxEntries", ToolParameter.ValueType.INTEGER, "最多返回条目数，默认 200，范围 1-500", false));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String requested = FileToolSupport.requiredString(call.arguments().get("path"), "path");
            int maxDepth = FileToolSupport.integer(
                    call.arguments().get("maxDepth"), "maxDepth", DEFAULT_MAX_DEPTH, 0, 5);
            int maxEntries = FileToolSupport.integer(
                    call.arguments().get("maxEntries"), "maxEntries", DEFAULT_MAX_ENTRIES, 1, 500);
            Path root = accessPolicy.authorizeRead(Path.of(requested));
            if (!Files.exists(root)) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "directory not found: " + root);
            }
            if (!Files.isDirectory(root)) {
                return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "path is not a directory: " + root);
            }

            List<String> entries = new ArrayList<>();
            List<Path> visiblePaths = visiblePaths(root, maxDepth);
            visiblePaths.sort(Comparator.comparing(path -> root.relativize(path).toString()));
            boolean truncated = visiblePaths.size() > maxEntries;
            visiblePaths.stream().limit(maxEntries).forEach(path -> {
                Path authorized = accessPolicy.authorizeRead(path);
                String suffix = Files.isSymbolicLink(authorized) ? " [symlink]"
                        : Files.isDirectory(authorized) ? "/" : "";
                entries.add(root.relativize(authorized).toString() + suffix);
            });
            if (entries.isEmpty()) {
                entries.add("[empty directory]");
            }
            if (truncated) {
                entries.add("[truncated after " + maxEntries + " entries]");
            }
            return ToolResult.success(String.join(System.lineSeparator(), entries));
        } catch (Exception exception) {
            return FileToolSupport.failure(exception, "directory list");
        }
    }

    private static List<Path> visiblePaths(Path root, int maxDepth) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, java.util.Set.of(), maxDepth + 1,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        if (directory.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (isHidden(directory)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        paths.add(directory);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file, BasicFileAttributes attributes) throws IOException {
                        if (!isHidden(file)) {
                            paths.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return paths;
    }

    private static boolean isHidden(Path path) throws IOException {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().startsWith(".")
                || Files.isHidden(path);
    }
}
