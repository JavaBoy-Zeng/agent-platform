package com.github.agentos.tool.tools;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolParameter;
import com.github.agentos.tool.ToolResult;
import com.github.agentos.tool.file.FileAccessPolicy;
import com.github.agentos.tool.FileToolSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
        return "递归列出本地目录结构，不跟随符号链接；用于在读取文件前确认真实项目结构";
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
            boolean truncated;
            try (Stream<Path> paths = Files.walk(root, maxDepth + 1)) {
                List<Path> selected = paths
                        .filter(path -> !path.equals(root))
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .limit((long) maxEntries + 1)
                        .toList();
                truncated = selected.size() > maxEntries;
                selected.stream().limit(maxEntries).forEach(path -> {
                    Path authorized = accessPolicy.authorizeRead(path);
                    String suffix = Files.isSymbolicLink(authorized) ? " [symlink]"
                            : Files.isDirectory(authorized) ? "/" : "";
                    entries.add(root.relativize(authorized).toString() + suffix);
                });
            }
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
}
