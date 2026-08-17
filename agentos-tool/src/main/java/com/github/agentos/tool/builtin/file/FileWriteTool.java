package com.github.agentos.tool.builtin.file;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolActions;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.writer.DocxFileContentWriter;
import com.github.agentos.tool.builtin.file.writer.FileContentWriter;
import com.github.agentos.tool.builtin.file.writer.FileContentWriterFactory;
import com.github.agentos.tool.builtin.file.writer.TextFileContentWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 创建、覆盖或追加 TXT、Markdown 和 Word OOXML 文档。 */
public final class FileWriteTool implements AgentTool {

    private final FileAccessPolicy accessPolicy;
    private final FileContentWriterFactory writerFactory;

    public FileWriteTool(FileAccessPolicy accessPolicy) {
        this(accessPolicy, new FileContentWriterFactory(List.of(
                new TextFileContentWriter(), new DocxFileContentWriter())));
    }

    public FileWriteTool(
            FileAccessPolicy accessPolicy, FileContentWriterFactory writerFactory) {
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy, "accessPolicy must not be null");
        this.writerFactory = Objects.requireNonNull(
                writerFactory, "writerFactory must not be null");
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public String description() {
        return "写入 .txt、.md 或真实 .docx 文件；不支持旧版 .doc。默认仅创建新文件，也可明确选择覆盖或追加。该操作会修改文件系统，需要人工审批";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "path", ToolParameter.ValueType.STRING,
                        "目标文件完整路径，扩展名必须是 .txt、.md、.markdown 或 .docx", true),
                new ToolParameter(
                        "content", ToolParameter.ValueType.STRING,
                        "要写入的文本内容；DOCX 会按换行符生成 Word 段落，可为空字符串", true),
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
            FileContentWriter writer = writerFactory.require(path);
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
            mode.write(writer, path, content);
            int bytesWritten = content.getBytes(StandardCharsets.UTF_8).length;
            Map<String, Object> data = Map.of(
                    "path", path.toString(),
                    "format", writer.format(),
                    "mode", mode.name(),
                    "created", created,
                    "bytesWritten", bytesWritten,
                    "fileSizeBytes", Files.size(path));
            return ToolResult.success(
                    data,
                    "file written successfully",
                    Map.of(
                            "charset", StandardCharsets.UTF_8.name(),
                            "supportedExtensions", List.of("txt", "md", "markdown", "docx")),
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

    private enum WriteMode {
        CREATE_NEW,
        OVERWRITE,
        APPEND;

        private void write(FileContentWriter writer, Path path, String content)
                throws java.io.IOException {
            switch (this) {
                case CREATE_NEW -> writer.create(path, content);
                case OVERWRITE -> writer.overwrite(path, content);
                case APPEND -> writer.append(path, content);
            }
        }

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
