package com.github.agentos.tool.writer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/** 使用 UTF-8 写入 TXT 和 Markdown 文件。 */
public final class TextFileContentWriter implements FileContentWriter {

    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "markdown");

    @Override
    public boolean supports(Path path) {
        return EXTENSIONS.contains(extension(path));
    }

    @Override
    public String format() {
        return "TEXT";
    }

    @Override
    public void create(Path path, String content) throws IOException {
        Files.writeString(
                path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public void overwrite(Path path, String content) throws IOException {
        Files.writeString(
                path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void append(Path path, String content) throws IOException {
        Files.writeString(
                path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String extension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
