package com.github.agentos.tool.builtin.file;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.reader.FileReaderFactory;
import com.github.agentos.tool.builtin.file.reader.FileReader;
import com.github.agentos.tool.builtin.file.reader.PagedFileReader;
import com.github.agentos.tool.builtin.file.reader.PagedReadResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class FileReadTool implements AgentTool {

    private static final int READ_CONTENT_CHARS = 3_000;

    private final FileAccessPolicy accessPolicy;

    private final FileReaderFactory readerFactory;


    public FileReadTool(FileAccessPolicy accessPolicy, FileReaderFactory readerFactory) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy);
        this.readerFactory = Objects.requireNonNull(readerFactory);
    }


    @Override
    public String name() {
        return "file_read";
    }


    @Override
    public String description() {
        return """
                读取本地文件内容。
                支持文本文件以及常见办公文档格式。所有格式都进行有界读取，
                每次返回 path、续读位置、hasMore 和 truncated 元数据。
                PDF 使用 nextPage 与 nextOffset 续读；文本、Markdown 和 DOCX 等线性格式
                使用 nextOffset 续读。当 hasMore=true 时完整提取任务不得提前结束。
                根据文件类型自动选择解析器。
                未知项目结构时应先使用 directory_list 或 file_search。
                受保护的部署配置文件禁止读取。
                """;
    }

    /**
     * 声明模型 Schema 与运行时执行共同使用的文件路径参数。
     *
     * <p>{@code path} 必须是完整文件路径；该契约必须与 {@link #(ToolCall)} 中读取的
     * 参数名称保持一致。</p>
     */
    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "path",
                        ToolParameter.ValueType.STRING,
                        "要读取的文件完整路径",
                        true),
                new ToolParameter(
                        "page",
                        ToolParameter.ValueType.INTEGER,
                        "PDF 物理页码，从 1 开始；首次读取省略时默认为 1，续读时使用上次返回的 nextPage",
                        false),
                new ToolParameter(
                        "offset",
                        ToolParameter.ValueType.INTEGER,
                        "字符偏移，从 0 开始；首次读取省略时默认为 0，续读时使用上次返回的 nextOffset",
                        false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            String requested = FileToolSupport.requiredString(call.arguments().get("path"), "path");
            Path path = accessPolicy.authorizeRead(Path.of(requested));
            if (!Files.exists(path)) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "file not found: " + path);
            }
            FileReader reader = readerFactory.getReader(path);
            if (reader instanceof PagedFileReader pagedReader) {
                int page = FileToolSupport.integer(
                        call.arguments().get("page"), "page", 1, 1, Integer.MAX_VALUE);
                int offset = FileToolSupport.integer(
                        call.arguments().get("offset"), "offset", 0, 0, Integer.MAX_VALUE);
                return ToolResult.success(formatPagedResult(path,
                        pagedReader.readPage(path, page, offset, READ_CONTENT_CHARS)));
            }
            if (call.arguments().containsKey("page")) {
                return ToolResult.failure(
                        ToolFailureType.INVALID_ARGUMENT,
                        "page is only supported for paged file formats such as PDF");
            }
            int offset = FileToolSupport.integer(
                    call.arguments().get("offset"), "offset", 0, 0, Integer.MAX_VALUE);
            return ToolResult.success(formatLinearResult(path, reader.read(path), offset));

        } catch (Exception e) {
            return FileToolSupport.failure(e, "file read");
        }
    }

    private static String formatPagedResult(Path path, PagedReadResult result) {
        return """
                [file_read_metadata]
                format=pdf
                path=%s
                page=%d
                totalPages=%d
                offset=%d
                returnedChars=%d
                pageChars=%d
                hasMore=%s
                nextPage=%d
                nextOffset=%d
                truncated=%s
                [/file_read_metadata]
                [content]
                %s
                """.formatted(
                metadataPath(path),
                result.page(),
                result.totalPages(),
                result.offset(),
                result.content().length(),
                result.pageChars(),
                result.hasMore(),
                result.nextPage(),
                result.nextOffset(),
                result.truncated(),
                result.content());
    }

    private static String formatLinearResult(Path path, String value, int offset) {
        String content = value == null ? "" : value;
        if (offset > content.length()) {
            throw new IllegalArgumentException(
                    "offset " + offset + " exceeds totalChars " + content.length());
        }
        int end = Math.min(content.length(), offset + READ_CONTENT_CHARS);
        if (end < content.length()
                && end > offset
                && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        boolean hasMore = end < content.length();
        return """
                [file_read_metadata]
                format=linear
                path=%s
                page=0
                totalPages=0
                offset=%d
                returnedChars=%d
                totalChars=%d
                hasMore=%s
                nextPage=0
                nextOffset=%d
                truncated=%s
                [/file_read_metadata]
                [content]
                %s
                """.formatted(
                metadataPath(path),
                offset,
                end - offset,
                content.length(),
                hasMore,
                hasMore ? end : 0,
                hasMore,
                content.substring(offset, end));
    }

    private static String metadataPath(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
