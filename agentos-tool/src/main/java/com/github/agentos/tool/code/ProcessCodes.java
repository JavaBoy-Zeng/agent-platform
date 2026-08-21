package com.github.agentos.tool.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 本地与容器执行器共享的进程级执行辅助。
 *
 * <p>负责源文件命名、落盘以及“启动进程 + 并行读取双输出流 + 超时强杀 +
 * 输出截断”的统一流程，避免两个执行器各自维护一份易错的进程处理代码。</p>
 */
final class ProcessCodes {

    private ProcessCodes() {
    }

    /** 宿主机是否为 Windows；容器内执行始终按 POSIX 处理，不使用本方法。 */
    static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
    }

    /** 返回 POSIX 环境下语言对应的源文件名（Docker 沙箱固定走这一分支）。 */
    static String sourceFileName(CodeLanguage language) {
        return sourceFileName(language, false);
    }

    /**
     * 返回语言对应的源文件名；Java 遵循单文件源码模式约定入口类为 {@code Main}。
     *
     * <p>Shell 脚本的扩展名必须与解释器匹配：POSIX 下由 {@code /bin/sh} 执行 {@code .sh}，
     * Windows 下由 {@code cmd} 执行 {@code .cmd}——扩展名错误会让 cmd 拒绝执行。</p>
     *
     * @param windows 是否按 Windows 约定命名
     */
    static String sourceFileName(CodeLanguage language, boolean windows) {
        return switch (language) {
            case PYTHON -> "main.py";
            case SHELL -> windows ? "script.cmd" : "script.sh";
            case JAVA -> "Main.java";
        };
    }

    /** 把源码按 POSIX 命名约定写入指定目录，返回写好的文件路径。 */
    static Path writeSource(Path directory, CodeLanguage language, String code) {
        return writeSource(directory, language, code, false);
    }

    /**
     * 把源码写入指定目录，返回写好的文件路径。
     *
     * @param windows 是否按 Windows 命名约定选择文件名
     */
    static Path writeSource(
            Path directory, CodeLanguage language, String code, boolean windows) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(sourceFileName(language, windows));
            Files.writeString(file, code, StandardCharsets.UTF_8);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to write source file", exception);
        }
    }

    /**
     * 运行一条命令并保证异常不越过执行边界。
     *
     * @param command        命令及参数
     * @param workDir        进程工作目录
     * @param timeoutSeconds 超时秒数
     * @param maxOutputChars 单流输出截断上限
     * @param executorName   执行器名称（写入结果与日志）
     * @return 标准化执行结果
     */
    static CodeExecutionResult run(
            List<String> command, Path workDir, long timeoutSeconds,
            int maxOutputChars, String executorName) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        long startedAt = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return new CodeExecutionResult(
                    -1, "", "failed to start: " + exception.getMessage(),
                    elapsed(startedAt), executorName, false);
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readStream(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readStream(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CodeExecutionResult(
                    -1, "", "execution interrupted",
                    elapsed(startedAt), executorName, false);
        }
        if (!finished) {
            process.destroyForcibly();
            return CodeExecutionResult.timeout(executorName, elapsed(startedAt));
        }
        int exitCode = process.exitValue();
        String out = truncate(join(stdout), maxOutputChars);
        String err = truncate(join(stderr), maxOutputChars);
        return new CodeExecutionResult(
                exitCode, out, err, elapsed(startedAt), executorName, false);
    }

    private static String readStream(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "(failed to read stream: " + exception.getMessage() + ")";
        }
    }

    private static String join(CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "(output unavailable)";
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars)
                + "\n... (output truncated at " + maxChars + " characters)";
    }

    private static long elapsed(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
