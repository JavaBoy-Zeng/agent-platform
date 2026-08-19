package com.github.agentos.tool.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessCodeExecutorTest {

    private final LocalProcessCodeExecutor executor = new LocalProcessCodeExecutor(10, 2000);

    @Test
    void metadataDescribesUnsandboxedLocalExecution() {
        assertThat(executor.name()).isEqualTo("local-process");
        assertThat(executor.isSandboxed()).isFalse();
        assertThat(executor.isAvailable()).isTrue();
        assertThat(executor.supports(CodeLanguage.PYTHON)).isTrue();
        assertThat(executor.supports(CodeLanguage.JAVA)).isTrue();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void executesShellCodeAndCapturesOutput() {
        CodeExecutionResult result = executor.execute(CodeExecutionRequest.of(
                CodeLanguage.SHELL, "echo hello-from-shell"));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-from-shell");
        assertThat(result.executor()).isEqualTo("local-process");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void nonZeroExitReportsFailureWithStderr() {
        CodeExecutionResult result = executor.execute(CodeExecutionRequest.of(
                CodeLanguage.SHELL, "echo oops >&2; exit 3"));

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("oops");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void timedOutExecutionIsKilled() {
        CodeExecutionResult result = executor.execute(new CodeExecutionRequest(
                CodeLanguage.SHELL, "sleep 30", 1));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void constructorValidatesLimits() {
        assertThatThrownBy(() -> new LocalProcessCodeExecutor(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProcessCodeExecutor(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandForUsesLanguageRuntimes() {
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.PYTHON, java.nio.file.Path.of("/tmp/main.py")))
                .containsExactly("python3", "/tmp/main.py");
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.JAVA, java.nio.file.Path.of("/tmp/Main.java")))
                .containsExactly("java", "Main.java");
    }
}
