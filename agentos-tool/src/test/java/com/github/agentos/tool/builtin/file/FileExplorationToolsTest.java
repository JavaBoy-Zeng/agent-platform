package com.github.agentos.tool.builtin.file;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.reader.FileReaderFactory;
import com.github.agentos.tool.builtin.file.access.AllowAllReadableFileAccessPolicy;
import com.github.agentos.tool.builtin.file.reader.PdfFileReader;
import com.github.agentos.tool.builtin.file.reader.TextFileReader;
import com.github.agentos.tool.builtin.file.DirectoryListTool;
import com.github.agentos.tool.builtin.file.FileReadTool;
import com.github.agentos.tool.builtin.file.FileSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

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

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall("directory_list", Map.of(
                "path", directory.toString(),
                "maxDepth", 2,
                "maxEntries", 10)));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains(
                "pom.xml", "module/", Path.of("module", "src") + "/");
    }

    @Test
    void excludesHiddenFilesAndPrunesHiddenDirectories() throws Exception {
        Files.createDirectories(directory.resolve(".git/objects"));
        Files.createDirectories(directory.resolve("visible/.cache"));
        Files.writeString(directory.resolve(".env"), "SECRET=value", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".git/config"), "hidden", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".git/objects/object"), "hidden", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("visible/readme.md"), "visible", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("visible/.cache/item"), "hidden", StandardCharsets.UTF_8);
        DirectoryListTool tool = new DirectoryListTool(new AllowAllReadableFileAccessPolicy());

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall("directory_list", Map.of(
                "path", directory.toString(),
                "maxDepth", 5,
                "maxEntries", 100)));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("visible/", Path.of("visible", "readme.md").toString());
        assertThat(result.output()).doesNotContain(".env", ".git", ".cache", "SECRET=value");
    }

    @Test
    void searchesByNameAndLiteralContent() throws Exception {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(
                directory.resolve("src/MainAgent.java"),
                "line one\nclass MainAgent {}\n",
                StandardCharsets.UTF_8);
        FileSearchTool tool = new FileSearchTool(new AllowAllReadableFileAccessPolicy());

        ToolResult names = tool.execute(ToolContexts.testContext(tool), new ToolCall("file_search", Map.of(
                "path", directory.toString(),
                "mode", "NAME",
                "query", "*.java")));
        ToolResult contents = tool.execute(ToolContexts.testContext(tool), new ToolCall("file_search", Map.of(
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

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall("file_search", Map.of(
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

        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("path", "page", "offset");
        assertThat(tool.parameters().get(0).required()).isTrue();
        assertThat(tool.parameters().get(1).required()).isFalse();
        assertThat(tool.parameters().get(2).required()).isFalse();
        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("report body");
    }

    @Test
    void readsPdfByPageAndReturnsExplicitContinuationMetadata() throws Exception {
        Path file = directory.resolve("resume.pdf");
        try (PDDocument document = new PDDocument()) {
            addPdfPage(document, "first company");
            addPdfPage(document, "second company");
            document.save(file.toFile());
        }
        FileReadTool tool = new FileReadTool(
                new AllowAllReadableFileAccessPolicy(),
                new FileReaderFactory(List.of(new PdfFileReader())));

        ToolResult first = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString())));
        ToolResult second = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString(), "page", 2, "offset", 0)));

        assertThat(first.success()).isTrue();
        assertThat(first.output()).hasSizeLessThan(4_000);
        assertThat(first.output())
                .contains("page=1", "totalPages=2", "hasMore=true", "nextPage=2")
                .contains("truncated=false", "first company")
                .doesNotContain("second company");
        assertThat(second.success()).isTrue();
        assertThat(second.output())
                .contains("page=2", "totalPages=2", "hasMore=false", "nextPage=0")
                .contains("truncated=false", "second company");
    }

    @Test
    void rejectsPdfPageOutsideDocumentBounds() throws Exception {
        Path file = directory.resolve("one-page.pdf");
        try (PDDocument document = new PDDocument()) {
            addPdfPage(document, "only page");
            document.save(file.toFile());
        }
        FileReadTool tool = new FileReadTool(
                new AllowAllReadableFileAccessPolicy(),
                new FileReaderFactory(List.of(new PdfFileReader())));

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString(), "page", 2)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(result.error()).contains("exceeds totalPages 1");
    }

    @Test
    void continuesWithinAnOversizedPdfPageUsingReturnedOffset() throws Exception {
        Path file = directory.resolve("long-page.pdf");
        String pageText = "A".repeat(3_100);
        try (PDDocument document = new PDDocument()) {
            addPdfPage(document, pageText);
            document.save(file.toFile());
        }
        FileReadTool tool = new FileReadTool(
                new AllowAllReadableFileAccessPolicy(),
                new FileReaderFactory(List.of(new PdfFileReader())));

        ToolResult first = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString())));
        ToolResult remainder = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "file_read", Map.of("path", file.toString(), "page", 1, "offset", 3_000)));

        assertThat(first.success()).isTrue();
        assertThat(first.output())
                .contains("returnedChars=3000", "hasMore=true", "nextPage=1")
                .contains("nextOffset=3000", "truncated=true");
        assertThat(remainder.success()).isTrue();
        assertThat(remainder.output())
                .contains("offset=3000", "returnedChars=100", "hasMore=false")
                .contains("nextPage=0", "nextOffset=0", "truncated=false");
    }

    private static void addPdfPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
