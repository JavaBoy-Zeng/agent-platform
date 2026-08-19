package com.github.agentos.tool.builtin.file;

import com.github.agentos.kernel.Artifact;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolActions;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.writer.DocxFileContentWriter;
import com.github.agentos.tool.builtin.file.writer.FileContentWriter;
import com.github.agentos.tool.builtin.file.writer.FileContentWriterFactory;
import com.github.agentos.tool.builtin.file.writer.TextFileContentWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    public ToolResult execute(ToolContext context, ToolCall call) {
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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path.toString());
            data.put("format", writer.format());
            data.put("mode", mode.name());
            data.put("created", created);
            data.put("bytesWritten", bytesWritten);
            data.put("fileSizeBytes", Files.size(path));
            registerArtifact(context, path).ifPresent(artifact -> {
                data.put("artifactId", artifact.artifactId());
                data.put("artifactFilename", artifact.filename());
            });
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

    /**
     * 把写入完成的文件登记为会话产物，供 REST 下载与管理。
     *
     * <p>产物登记是附加能力：存储不可用或登记失败时静默降级，
     * 不影响文件写入本体的成功语义。</p>
     */
    private static Optional<Artifact> registerArtifact(ToolContext context, Path path) {
        try {
            return context.artifacts().save(
                    context.sessionId(),
                    context.invocationId(),
                    path.getFileName().toString(),
                    contentTypeFor(path),
                    Files.readAllBytes(path));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** 按扩展名推断产物的 MIME 类型。 */
    private static String contentTypeFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
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
