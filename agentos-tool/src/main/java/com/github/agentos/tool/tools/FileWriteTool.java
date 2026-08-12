package com.github.agentos.tool.tools;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.FileToolSupport;
import com.github.agentos.tool.ToolActions;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolParameter;
import com.github.agentos.tool.ToolResult;
import com.github.agentos.tool.file.FileAccessPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 使用 UTF-8 创建、覆盖或追加本地文本文件。 */
public final class FileWriteTool implements AgentTool {

    private final FileAccessPolicy accessPolicy;

    public FileWriteTool(FileAccessPolicy accessPolicy) {
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy, "accessPolicy must not be null");
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public String description() {
        return "使用 UTF-8 写入本地文本文件；默认仅创建新文件，也可明确选择覆盖或追加。该操作会修改文件系统，需要人工审批";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "path", ToolParameter.ValueType.STRING, "目标文件完整路径", true),
                new ToolParameter(
                        "content", ToolParameter.ValueType.STRING, "要写入的 UTF-8 文本内容，可为空字符串", true),
                new ToolParameter(
                        "mode", ToolParameter.ValueType.STRING,
                        "写入模式：CREATE_NEW（默认，仅新建）、OVERWRITE（覆盖）或 APPEND（追加）", false),
                new ToolParameter(
                        "createParentDirectories", ToolParameter.ValueType.BOOLEAN,
                        "父目录不存在时是否创建，默认 false", false));
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String requested = FileToolSupport.requiredString(
                    call.arguments().get("path"), "path");
            String content = requiredContent(call.arguments().get("content"));
            WriteMode mode = WriteMode.parse(call.arguments().get("mode"));
            boolean createParents = FileToolSupport.bool(
                    call.arguments().get("createParentDirectories"),
                    "createParentDirectories", false);
            Path path = accessPolicy.authorizeWrite(Path.of(requested));
            Path parent = path.getParent();
            if (parent == null) {
                return ToolResult.failure(
                        ToolFailureType.INVALID_ARGUMENT,
                        "file write failed: target path has no parent directory: " + path);
            }
            if (createParents) {
                Files.createDirectories(parent);
            } else if (!Files.exists(parent)) {
                return ToolResult.failure(
                        ToolFailureType.NOT_FOUND,
                        "file write failed: parent directory not found: " + parent);
            }
            if (!Files.isDirectory(parent)) {
                return ToolResult.failure(
                        ToolFailureType.INVALID_ARGUMENT,
                        "file write failed: parent path is not a directory: " + parent);
            }

            boolean created = !Files.exists(path);
            write(path, content, mode);
            int bytesWritten = content.getBytes(StandardCharsets.UTF_8).length;
            Map<String, Object> data = Map.of(
                    "path", path.toString(),
                    "mode", mode.name(),
                    "created", created,
                    "bytesWritten", bytesWritten);
            return ToolResult.success(
                    data,
                    "file written successfully",
                    Map.of("charset", StandardCharsets.UTF_8.name()),
                    ToolActions.none());
        } catch (Exception exception) {
            return FileToolSupport.failure(exception, "file write");
        }
    }

    private static String requiredContent(Object value) {
        if (!(value instanceof String content)) {
            throw new IllegalArgumentException("missing or invalid required argument: content");
        }
        return content;
    }

    private static void write(Path path, String content, WriteMode mode) throws java.io.IOException {
        switch (mode) {
            case CREATE_NEW -> Files.writeString(
                    path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            case OVERWRITE -> Files.writeString(
                    path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            case APPEND -> Files.writeString(
                    path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private enum WriteMode {
        CREATE_NEW,
        OVERWRITE,
        APPEND;

        private static WriteMode parse(Object value) {
            if (value == null) {
                return CREATE_NEW;
            }
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("mode must be a non-blank string");
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "mode must be CREATE_NEW, OVERWRITE, or APPEND");
            }
        }
    }
}
