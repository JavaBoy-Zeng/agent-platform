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
        assertThat(executor.supports(CodeLanguage.JAVASCRIPT)).isTrue();
        assertThat(executor.supports(CodeLanguage.GO)).isTrue();
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
    void commandForUsesPosixLanguageRuntimes() {
        java.nio.file.Path python = java.nio.file.Path.of("/tmp/main.py");
        java.nio.file.Path shell = java.nio.file.Path.of("/tmp/script.sh");
        assertThat(LocalProcessCodeExecutor.commandFor(CodeLanguage.PYTHON, python, false))
                .containsExactly("python3", python.toString());
        assertThat(LocalProcessCodeExecutor.commandFor(CodeLanguage.SHELL, shell, false))
                .containsExactly("/bin/sh", shell.toString());
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.JAVA, java.nio.file.Path.of("/tmp/Main.java"), false))
                .containsExactly("java", "Main.java");
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.JAVASCRIPT, java.nio.file.Path.of("/tmp/main.js"), false))
                .containsExactly("node", "/tmp/main.js");
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.GO, java.nio.file.Path.of("/tmp/main.go"), false))
                .containsExactly("go", "run", "/tmp/main.go");
    }

    /**
     * Windows 上 {@code python3} 命中 Microsoft Store 别名占位程序（退出码 49，
     * 不执行脚本），{@code /bin/sh} 不存在，因此必须切换到 {@code python} 与
     * {@code cmd /c}。
     */
    @Test
    void commandForUsesWindowsLanguageRuntimes() {
        java.nio.file.Path python = java.nio.file.Path.of("C:/tmp/main.py");
        java.nio.file.Path shell = java.nio.file.Path.of("C:/tmp/script.cmd");
        assertThat(LocalProcessCodeExecutor.commandFor(CodeLanguage.PYTHON, python, true))
                .containsExactly("python", python.toString());
        assertThat(LocalProcessCodeExecutor.commandFor(CodeLanguage.SHELL, shell, true))
                .containsExactly("cmd", "/c", shell.toString());
        assertThat(LocalProcessCodeExecutor.commandFor(
                CodeLanguage.JAVA, java.nio.file.Path.of("C:/tmp/Main.java"), true))
                .containsExactly("java", "Main.java");
    }

    /** Shell 脚本扩展名必须与解释器匹配，否则 cmd 拒绝执行。 */
    @Test
    void sourceFileNameFollowsPlatformShellConvention() {
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.SHELL, false)).isEqualTo("script.sh");
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.SHELL, true)).isEqualTo("script.cmd");
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.PYTHON, true)).isEqualTo("main.py");
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.JAVA, true)).isEqualTo("Main.java");
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.JAVASCRIPT, true)).isEqualTo("main.js");
        assertThat(ProcessCodes.sourceFileName(CodeLanguage.GO, true)).isEqualTo("main.go");
    }

    /** 在真实宿主机上跑通一次 shell 执行，覆盖平台分支的实际可用性。 */
    @Test
    void executesShellOnCurrentPlatform() {
        CodeExecutionResult result = executor.execute(CodeExecutionRequest.of(
                CodeLanguage.SHELL, "echo hello-from-shell"));

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("hello-from-shell");
    }
}
