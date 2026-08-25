package com.github.agentos.tool.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSandboxExecutorTest {

    @TempDir
    Path tempDir;

    private final DockerSandboxExecutor executor = new DockerSandboxExecutor(10, 2000);

    @Test
    void metadataDescribesSandboxedExecution() {
        assertThat(executor.name()).isEqualTo("docker-sandbox");
        assertThat(executor.isSandboxed()).isTrue();
        assertThat(executor.supports(CodeLanguage.PYTHON)).isTrue();
        assertThat(executor.supports(CodeLanguage.SHELL)).isTrue();
        assertThat(executor.supports(CodeLanguage.JAVA)).isTrue();
    }

    @Test
    void containerCommandEnforcesIsolationFlags() {
        List<String> command = executor.containerCommand(
                CodeLanguage.PYTHON, tempDir, "main.py");

        assertThat(command).containsSubsequence(
                "docker", "run", "--rm", "--network", "none",
                "--memory", "256m", "--cpus", "0.5");
        assertThat(command).contains(tempDir + ":/sandbox:ro");
        assertThat(command).contains("-w", "/tmp");
        assertThat(command).contains("python:3.12-slim");
        assertThat(command).containsSubsequence("python3", "/sandbox/main.py");
    }

    @Test
    void containerCommandUsesImagePerLanguage() {
        List<String> shell = executor.containerCommand(
                CodeLanguage.SHELL, tempDir, "script.sh");
        List<String> java = executor.containerCommand(
                CodeLanguage.JAVA, tempDir, "Main.java");

        assertThat(shell).contains("alpine:3.20");
        assertThat(shell).containsSubsequence("/bin/sh", "/sandbox/script.sh");
        assertThat(java).contains("eclipse-temurin:21-jdk-alpine");
        assertThat(java).containsSubsequence("java", "/sandbox/Main.java");
    }

    @Test
    void customImagesAndLimitsAreHonored() {
        DockerSandboxExecutor custom = new DockerSandboxExecutor(
                java.util.Map.of(CodeLanguage.PYTHON, "my-python:3"), "512m", "1.0", 5, 500);

        List<String> command = custom.containerCommand(
                CodeLanguage.PYTHON, tempDir, "main.py");

        assertThat(command).contains("--memory", "512m", "--cpus", "1.0", "my-python:3");
    }

    @Test
    void unsupportedLanguageReturnsFailureResult() {
        DockerSandboxExecutor shellOnly = new DockerSandboxExecutor(
                java.util.Map.of(CodeLanguage.SHELL, "alpine:3.20"), "256m", "0.5", 5, 500);

        CodeExecutionResult result = shellOnly.execute(
                CodeExecutionRequest.of(CodeLanguage.PYTHON, "print('hi')"));

        assertThat(result.success()).isFalse();
        assertThat(result.stderr()).contains("unsupported language");
    }

    @Test
    void isAvailableReflectsDockerDaemonAndLocalImagesWithoutThrowing() {
        // 不论守护进程/镜像是否就绪，探测都应安静返回布尔值且结果被缓存。
        boolean first = executor.isAvailable();
        assertThat(executor.isAvailable()).isEqualTo(first);
    }
}
