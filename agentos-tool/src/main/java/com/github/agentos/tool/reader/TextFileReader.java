package com.github.agentos.tool.reader;

import com.github.agentos.tool.FileReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class TextFileReader implements FileReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt",
            "md",
            "markdown",
            "java",
            "xml",
            "json",
            "yml",
            "yaml",
            "properties",
            "gradle",
            "kts",
            "sql",
            "html",
            "css",
            "js",
            "ts",
            "vue",
            "py",
            "go",
            "sh",
            "bat",
            "cmd"
    );

    @Override
    public boolean supports(Path path) {
//        String name = path.getFileName().toString().toLowerCase();
//        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".java");

        if (path == null) {
            return false;
        }

        String fileName = path.getFileName()
                .toString()
                .toLowerCase();

        int dotIndex = fileName.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }

        String extension = fileName.substring(dotIndex + 1);

        return SUPPORTED_EXTENSIONS.contains(extension);
    }


    @Override
    public String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}