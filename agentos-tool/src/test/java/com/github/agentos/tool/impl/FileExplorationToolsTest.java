package com.github.agentos.tool.impl;

import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolResult;
import com.github.agentos.tool.FileReaderFactory;
import com.github.agentos.tool.file.AllowAllReadableFileAccessPolicy;
import com.github.agentos.tool.reader.TextFileReader;
import com.github.agentos.tool.tools.DirectoryListTool;
import com.github.agentos.tool.tools.FileReadTool;
import com.github.agentos.tool.tools.FileSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileExplorationToolsTest {

    @TempDir
    Path directory;

    @Test
    void listsRealDirectoryEntriesWithinBounds() throws Exception {
        Files.createDirectories(directory.resolve("module/src"));
        Files.writeString(directory.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("module/src/App.java"), "class App {}", StandardCharsets.UTF_8);
        DirectoryListTool tool = new DirectoryListTool(new AllowAllReadableFileAccessPolicy());

        ToolResult result = tool.execute(new ToolCall("directory_list", Map.of(
                "path", directory.toString(),
                "maxDepth", 2,
                "maxEntries", 10)));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("pom.xml", "module/", "module\\src/");
    }

    @Test
    void searchesByNameAndLiteralContent() throws Exception {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(
                directory.resolve("src/MainAgent.java"),
                "line one\nclass MainAgent {}\n",
                StandardCharsets.UTF_8);
        FileSearchTool tool = new FileSearchTool(new AllowAllReadableFileAccessPolicy());

        ToolResult names = tool.execute(new ToolCall("file_search", Map.of(
                "path", directory.toString(),
                "mode", "NAME",
                "query", "*.java")));
        ToolResult contents = tool.execute(new ToolCall("file_search", Map.of(
                "path", directory.toString(),
                "mode", "CONTENT",
                "query", "class mainagent",
                "caseSensitive", false)));

        assertThat(names.success()).isTrue();
        assertThat(names.output()).contains("MainAgent.java");
        assertThat(contents.success()).isTrue();
        assertThat(contents.output()).contains("MainAgent.java:2", "class MainAgent");
    }

    @Test
    void reportsMissingRootAsNotFound() {
        FileSearchTool tool = new FileSearchTool(new AllowAllReadableFileAccessPolicy());

        ToolResult result = tool.execute(new ToolCall("file_search", Map.of(
                "path", directory.resolve("missing").toString(),
                "mode", "NAME",
                "query", "*")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.NOT_FOUND);
    }

    @Test
    void fileReadDeclaresAndConsumesRequiredPathParameter() throws Exception {
        Path file = directory.resolve("report.txt");
        Files.writeString(file, "report body", StandardCharsets.UTF_8);
        FileReadTool tool = new FileReadTool(
                new AllowAllReadableFileAccessPolicy(),
                new FileReaderFactory(List.of(new TextFileReader())));

        assertThat(tool.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.name()).isEqualTo("path");
            assertThat(parameter.required()).isTrue();
        });
        ToolResult result = tool.execute(new ToolCall(
                "file_read", Map.of("path", file.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("report body");
    }
}
