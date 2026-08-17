package com.github.agentos.tool.builtin.file;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.AllowAllFileAccessPolicy;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FileWriteToolTest {

    @TempDir
    Path directory;

    @Test
    void createsNewUtf8FileByDefault() throws Exception {
        Path file = directory.resolve("你好.txt");
        FileWriteTool tool = tool();

        ToolResult result = tool.execute(call(file, "中文内容", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("中文内容");
        assertThat(result.data()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("mode")).isEqualTo("CREATE_NEW");
        assertThat(data.get("created")).isEqualTo(true);
        assertThat(data.get("bytesWritten")).isEqualTo(12);
    }

    @Test
    void defaultModeDoesNotOverwriteExistingFile() throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "original", StandardCharsets.UTF_8);

        ToolResult result = tool().execute(call(file, "replacement", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(Files.readString(file)).isEqualTo("original");
    }

    @Test
    void overwritesAndAppendsOnlyWhenExplicitlyRequested() throws Exception {
        Path file = directory.resolve("modes.txt");
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        FileWriteTool tool = tool();

        ToolResult overwritten = tool.execute(call(
                file, "new", Map.of("mode", "OVERWRITE")));
        ToolResult appended = tool.execute(call(
                file, "+tail", Map.of("mode", "append")));

        assertThat(overwritten.success()).isTrue();
        assertThat(appended.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("new+tail");
    }

    @Test
    void optionallyCreatesMissingParentDirectories() throws Exception {
        Path file = directory.resolve("nested/deep/file.txt");

        ToolResult rejected = tool().execute(call(file, "body", Map.of()));
        ToolResult created = tool().execute(call(
                file, "body", Map.of("createParentDirectories", true)));

        assertThat(rejected.success()).isFalse();
        assertThat(rejected.failureType()).isEqualTo(ToolFailureType.NOT_FOUND);
        assertThat(created.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("body");
    }

    @Test
    void createsRealDocxAndPreservesParagraphs() throws Exception {
        Path file = directory.resolve("report.docx");

        ToolResult result = tool().execute(call(file, "标题\n第一段\n第二段", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(Files.readAllBytes(file)).startsWith(0x50, 0x4b);
        assertThat(readDocx(file)).isEqualTo("标题\n第一段\n第二段");
        assertThat(((Map<?, ?>) result.data()).get("format")).isEqualTo("DOCX");
    }

    @Test
    void appendsParagraphsToExistingDocx() throws Exception {
        Path file = directory.resolve("append.docx");
        FileWriteTool tool = tool();

        ToolResult created = tool.execute(call(file, "第一段", Map.of()));
        ToolResult appended = tool.execute(call(file, "第二段\n第三段", Map.of("mode", "APPEND")));

        assertThat(created.success()).isTrue();
        assertThat(appended.success()).isTrue();
        assertThat(readDocx(file)).isEqualTo("第一段\n第二段\n第三段");
    }

    @Test
    void rejectsLegacyDocAndUnknownExtensions() {
        ToolResult legacyDoc = tool().execute(call(
                directory.resolve("legacy.doc"), "body", Map.of()));
        ToolResult pdf = tool().execute(call(
                directory.resolve("report.pdf"), "body", Map.of()));

        assertThat(legacyDoc.success()).isFalse();
        assertThat(legacyDoc.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(legacyDoc.error()).contains("supported extensions are .txt, .md, and .docx");
        assertThat(pdf.success()).isFalse();
        assertThat(pdf.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void readOnlyPolicyDeniesWritesByDefault() {
        FileAccessPolicy readOnly = requested -> requested.toAbsolutePath().normalize();
        FileWriteTool tool = new FileWriteTool(readOnly);

        ToolResult result = tool.execute(call(
                directory.resolve("denied.txt"), "body", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.SECURITY_DENIED);
        assertThat(Files.exists(directory.resolve("denied.txt"))).isFalse();
    }

    @Test
    void declaresHighRiskAndAllParameters() {
        FileWriteTool tool = tool();

        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
        assertThat(tool.parallelSafe()).isFalse();
        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("path", "content", "mode", "createParentDirectories");
        assertThat(tool.parameters()).extracting(parameter -> parameter.required())
                .containsExactly(true, true, false, false);
    }

    private FileWriteTool tool() {
        return new FileWriteTool(new AllowAllFileAccessPolicy());
    }

    private static String readDocx(Path path) throws Exception {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path))) {
            return document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static ToolCall call(
            Path path, String content, Map<String, Object> optionalArguments) {
        java.util.HashMap<String, Object> arguments = new java.util.HashMap<>(optionalArguments);
        arguments.put("path", path.toString());
        arguments.put("content", content);
        return new ToolCall("file_write", arguments);
    }
}
