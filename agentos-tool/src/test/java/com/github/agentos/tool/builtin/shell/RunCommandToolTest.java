package com.github.agentos.tool.builtin.shell;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** shell 执行工具单元测试。 */
class RunCommandToolTest {

    @TempDir
    Path tempDir;

    private final RunCommandTool tool =
            new RunCommandTool(Path.of("."), 30, 20000);

    @Test
    void executesSimpleCommandAndReturnsOutput() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("run_command", Map.of("command", "echo hello-agentos")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello-agentos");
    }

    @Test
    void reportsNonZeroExitCodeAsFailure() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("run_command", Map.of("command", "exit 3")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exited with code 3");
    }

    @Test
    void killsCommandExceedingTimeout() {
        RunCommandTool shortTimeout = new RunCommandTool(Path.of("."), 1, 20000);
        ToolResult result = shortTimeout.execute(ToolContexts.testContext(shortTimeout),
                new ToolCall("run_command", Map.of("command", "sleep 30")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType())
                .isEqualTo(com.github.agentos.tool.api.ToolFailureType.TIMEOUT);
    }

    @Test
    void truncatesOversizedOutput() {
        RunCommandTool tinyOutput = new RunCommandTool(Path.of("."), 30, 50);
        ToolResult result = tinyOutput.execute(ToolContexts.testContext(tinyOutput),
                new ToolCall("run_command", Map.of("command", "echo 012345678901234567890123456789012345678901234567890123456789")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).hasSizeLessThan(120).contains("truncated at 50");
    }

    @Test
    void runsInConfiguredWorkingDirectory(@TempDir Path otherDir) throws Exception {
        Path marker = otherDir.resolve("marker.txt");
        java.nio.file.Files.writeString(marker, "here");
        RunCommandTool scoped = new RunCommandTool(otherDir, 30, 20000);

        ToolResult result = scoped.execute(ToolContexts.testContext(scoped),
                new ToolCall("run_command", Map.of("command",
                        "ls marker.txt")));

        assertThat(result.success()).as(result.error()).isTrue();
    }

    @Test
    void rejectsBlankCommand() {
        assertThatThrownBy(() -> tool.execute(ToolContexts.testContext(tool),
                new ToolCall("run_command", Map.of("command", " "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> tool.execute(ToolContexts.testContext(tool),
                new ToolCall("run_command", Map.of("command", "echo hi", "timeout_seconds", 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declaresHighRiskAndParameterSchema() {
        assertThat(tool.riskLevel())
                .isEqualTo(com.github.agentos.tool.api.AgentTool.RiskLevel.HIGH);
        assertThat(tool.name()).isEqualTo("run_command");
        assertThat(tool.parameters()).hasSize(2);
    }

    @Test
    void descriptionIdentifiesServiceHostAndConfiguredWorkingDirectory() {
        RunCommandTool scoped = new RunCommandTool(tempDir, 30, 20000);

        assertThat(scoped.description())
                .contains("AgentOS 服务")
                .contains(tempDir.toAbsolutePath().normalize().toString())
                .contains("curl", "execute_code");
    }
}
