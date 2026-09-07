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
 * <p>代码写入临时目录后按语言与宿主机平台选择启动命令：Python 经
 * {@code python3}（Windows 为 {@code python}）、Shell 经 {@code /bin/sh}
 * （Windows 为 {@code cmd /c} 执行 {@code .cmd}）、Java 使用 JDK 11+
 * 单文件源码模式、JavaScript 使用 Node.js、Go 使用 {@code go run}。
 * 宿主机直连执行风险较高，
 * 对应工具应声明 {@code HIGH} 风险等级并走 HITL 审批。</p>
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
        boolean windows = ProcessCodes.isWindows();
        Path sandboxDir = null;
        try {
            sandboxDir = Files.createTempDirectory("agentos-code-");
            Path source = ProcessCodes.writeSource(
                    sandboxDir, request.language(), request.code(), windows);
            return ProcessCodes.run(
                    commandFor(request.language(), source, windows),
                    sandboxDir, timeout, maxOutputChars, name());
        } catch (IOException | UncheckedIOException exception) {
            return new CodeExecutionResult(
                    -1, "", "failed to prepare execution: " + exception.getMessage(),
                    0, name(), false);
        } finally {
            cleanup(sandboxDir);
        }
    }

    /** 构建当前宿主机平台下语言对应的本地启动命令。 */
    static List<String> commandFor(CodeLanguage language, Path source) {
        return commandFor(language, source, ProcessCodes.isWindows());
    }

    /**
     * 构建语言对应的本地启动命令。
     *
     * <p>解释器名称与宿主机平台强相关，不能共用一套：</p>
     * <ul>
     *   <li>Python：Windows 官方安装包只提供 {@code python}，而
     *       {@code python3} 命中的是 Microsoft Store 应用执行别名占位程序，
     *       它不会运行脚本，只以退出码 49 打开商店页面；</li>
     *   <li>Shell：Windows 无 {@code /bin/sh}，改用 {@code cmd /c} 执行
     *       {@code .cmd} 批处理，脚本内容按批处理语法书写。</li>
     * </ul>
     *
     * @param windows 是否按 Windows 约定构建命令
     */
    static List<String> commandFor(CodeLanguage language, Path source, boolean windows) {
        return switch (language) {
            case PYTHON -> List.of(windows ? "python" : "python3", source.toString());
            case SHELL -> windows
                    ? List.of("cmd", "/c", source.toString())
                    : List.of("/bin/sh", source.toString());
            case JAVA -> List.of("java", source.getFileName().toString());
            case JAVASCRIPT -> List.of("node", source.toString());
            case GO -> List.of("go", "run", source.toString());
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
