package com.github.agentos.tool;

import java.nio.file.Path;
import java.util.List;

public final class FileReaderFactory {


    private final List<FileReader> readers;


    public FileReaderFactory(List<FileReader> readers) {
        this.readers = readers;
    }


    public FileReader getReader(Path path) {
        return readers.stream()
                .filter(r -> r.supports(path))
                .findFirst()
                .orElseThrow(
                        () -> new RuntimeException(
                                "unsupported file type:"
                                        + path
                        )
                );
    }
}