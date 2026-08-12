package com.github.agentos.tool.impl;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolResult;
import com.github.agentos.tool.file.AllowAllFileAccessPolicy;
import com.github.agentos.tool.file.FileAccessPolicy;
import com.github.agentos.tool.tools.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    private static ToolCall call(
            Path path, String content, Map<String, Object> optionalArguments) {
        java.util.HashMap<String, Object> arguments = new java.util.HashMap<>(optionalArguments);
        arguments.put("path", path.toString());
        arguments.put("content", content);
        return new ToolCall("file_write", arguments);
    }
}
