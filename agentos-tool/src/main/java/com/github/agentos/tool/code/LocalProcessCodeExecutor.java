package com.github.agentos.tool.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 通过本地子进程直接执行代码的执行器（无沙箱）。
 *
 * <p>代码写入临时目录后按语言选择启动命令：Python 经 {@code python3}、
 * Shell 经 {@code /bin/sh}、Java 使用 JDK 11+ 单文件源码模式
 * （约定入口类为 {@code Main}）。宿主机直连执行风险较高，对应工具
 * 应声明 {@code HIGH} 风险等级并走 HITL 审批。</p>
 */
public final class LocalProcessCodeExecutor implements CodeExecutor {

    private final long defaultTimeoutSeconds;
    private final int maxOutputChars;

    /**
     * 创建本地进程执行器。
     *
     * @param defaultTimeoutSeconds 默认超时秒数（请求未指定时使用）
     * @param maxOutputChars        单流输出截断上限
     */
    public LocalProcessCodeExecutor(long defaultTimeoutSeconds, int maxOutputChars) {
        if (defaultTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("defaultTimeoutSeconds must be positive");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String name() {
        return "local-process";
    }

    @Override
    public boolean supports(CodeLanguage language) {
        return true;
    }

    @Override
    public boolean isSandboxed() {
        return false;
    }

    @Override
    public CodeExecutionResult execute(CodeExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long timeout = request.timeoutSeconds() > 0
                ? request.timeoutSeconds() : defaultTimeoutSeconds;
        Path sandboxDir = null;
        try {
            sandboxDir = Files.createTempDirectory("agentos-code-");
            Path source = ProcessCodes.writeSource(
                    sandboxDir, request.language(), request.code());
            return ProcessCodes.run(
                    commandFor(request.language(), source),
                    sandboxDir, timeout, maxOutputChars, name());
        } catch (IOException | UncheckedIOException exception) {
            return new CodeExecutionResult(
                    -1, "", "failed to prepare execution: " + exception.getMessage(),
                    0, name(), false);
        } finally {
            cleanup(sandboxDir);
        }
    }

    /** 构建语言对应的本地启动命令。 */
    static List<String> commandFor(CodeLanguage language, Path source) {
        return switch (language) {
            case PYTHON -> List.of("python3", source.toString());
            case SHELL -> List.of("/bin/sh", source.toString());
            case JAVA -> List.of("java", source.getFileName().toString());
        };
    }

    private static void cleanup(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响执行结果。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }
}
