package com.github.agentos.tool.builtin.file.writer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 根据目标扩展名选择文件内容 Writer。 */
public final class FileContentWriterFactory {

    private final List<FileContentWriter> writers;

    public FileContentWriterFactory(List<? extends FileContentWriter> writers) {
        this.writers = List.copyOf(Objects.requireNonNull(writers, "writers must not be null"));
        if (this.writers.isEmpty()) {
            throw new IllegalArgumentException("writers must not be empty");
        }
    }

    /** 获取支持目标格式的 Writer，不支持时返回清晰的参数错误。 */
    public FileContentWriter require(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        return writers.stream()
                .filter(writer -> writer.supports(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported file type; supported extensions are .txt, .md, and .docx: "
                                + path));
    }
}
