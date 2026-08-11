package com.github.agentos.tool.tools;

import com.github.agentos.tool.*;
import com.github.agentos.tool.file.FileAccessPolicy;
import com.github.agentos.tool.FileReaderFactory;
import com.github.agentos.tool.FileToolSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class FileReadTool implements AgentTool {


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
                支持文本文件以及常见办公文档格式。
                根据文件类型自动选择解析器。
                未知项目结构时应先使用 directory_list 或 file_search。
                """;
    }

    /**
     * 声明模型 Schema 与运行时执行共同使用的文件路径参数。
     *
     * <p>{@code path} 必须是完整文件路径；该契约必须与 {@link #execute(ToolCall)} 中读取的
     * 参数名称保持一致。</p>
     */
    @Override
    public List<ToolParameter> parameters() {
        return List.of(new ToolParameter(
                "path",
                ToolParameter.ValueType.STRING,
                "要读取的文件完整路径",
                true));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String requested = FileToolSupport.requiredString(call.arguments().get("path"), "path");
            Path path = accessPolicy.authorizeRead(Path.of(requested));
            if (!Files.exists(path)) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "file not found: " + path);
            }
            FileReader reader = readerFactory.getReader(path);
            return ToolResult.success(reader.read(path));

        } catch (Exception e) {
            return FileToolSupport.failure(e, "file read");
        }
    }
}
