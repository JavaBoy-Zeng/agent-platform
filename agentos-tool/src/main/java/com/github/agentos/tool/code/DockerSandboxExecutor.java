package com.github.agentos.tool.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以一次性 Docker 容器为沙箱的代码执行器。
 *
 * <p>安全边界：每次执行都启动全新容器（{@code --rm}），禁用网络
 * （{@code --network none}），限制内存与 CPU，源码目录以只读方式挂载，
 * 容器工作目录指向临时可写目录。沙箱内的失败被约束在容器内，
 * 对应工具可按较低风险等级接入。</p>
 */
public final class DockerSandboxExecutor implements CodeExecutor {

    private static final String MOUNT_POINT = "/sandbox";

    private final Map<CodeLanguage, String> images;
    private final String memoryLimit;
    private final String cpus;
    private final long defaultTimeoutSeconds;
    private final int maxOutputChars;
    private volatile Boolean available;

    /**
     * 使用默认镜像与资源限制创建沙箱执行器。
     *
     * @param defaultTimeoutSeconds 默认超时秒数（请求未指定时使用）
     * @param maxOutputChars        单流输出截断上限
     */
    public DockerSandboxExecutor(long defaultTimeoutSeconds, int maxOutputChars) {
        this(Map.of(
                CodeLanguage.PYTHON, "python:3.12-slim",
                CodeLanguage.SHELL, "alpine:3.20",
                CodeLanguage.JAVA, "eclipse-temurin:21-jdk-alpine",
                CodeLanguage.JAVASCRIPT, "node:22-alpine",
                CodeLanguage.GO, "golang:1.25-alpine"),
                "256m", "0.5", defaultTimeoutSeconds, maxOutputChars);
    }

    /**
     * 使用自定义镜像与资源限制创建沙箱执行器。
     *
     * @param images                语言到镜像的映射
     * @param memoryLimit           容器内存限制（如 {@code 256m}）
     * @param cpus                  容器 CPU 配额（如 {@code 0.5}）
     * @param defaultTimeoutSeconds 默认超时秒数
     * @param maxOutputChars        单流输出截断上限
     */
    public DockerSandboxExecutor(
            Map<CodeLanguage, String> images, String memoryLimit, String cpus,
            long defaultTimeoutSeconds, int maxOutputChars) {
        if (defaultTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("defaultTimeoutSeconds must be positive");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.images = Map.copyOf(Objects.requireNonNull(images, "images must not be null"));
        this.memoryLimit = Objects.requireNonNull(memoryLimit, "memoryLimit must not be null");
        this.cpus = Objects.requireNonNull(cpus, "cpus must not be null");
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String name() {
        return "docker-sandbox";
    }

    @Override
    public boolean supports(CodeLanguage language) {
        return images.containsKey(language);
    }

    @Override
    public boolean isSandboxed() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        if (available == null) {
            available = probeDocker();
        }
        return available;
    }

    @Override
    public CodeExecutionResult execute(CodeExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!supports(request.language())) {
            return new CodeExecutionResult(
                    -1, "", "unsupported language in docker sandbox: " + request.language(),
                    0, name(), false);
        }
        long timeout = request.timeoutSeconds() > 0
                ? request.timeoutSeconds() : defaultTimeoutSeconds;
        Path hostDir = null;
        try {
            hostDir = Files.createTempDirectory("agentos-sandbox-");
            Path source = ProcessCodes.writeSource(hostDir, request.language(), request.code());
            return ProcessCodes.run(
                    containerCommand(request.language(), hostDir, source.getFileName().toString()),
                    hostDir, timeout, maxOutputChars, name());
        } catch (IOException | UncheckedIOException exception) {
            return new CodeExecutionResult(
                    -1, "", "failed to prepare execution: " + exception.getMessage(),
                    0, name(), false);
        } finally {
            cleanup(hostDir);
        }
    }

    /** 构建 {@code docker run --rm --network none ...} 沙箱命令。 */
    List<String> containerCommand(CodeLanguage language, Path hostDir, String sourceFile) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--network");
        command.add("none");
        command.add("--memory");
        command.add(memoryLimit);
        command.add("--cpus");
        command.add(cpus);
        command.add("-v");
        command.add(hostDir + ":" + MOUNT_POINT + ":ro");
        command.add("-w");
        command.add("/tmp");
        command.add(images.get(language));
        command.addAll(runtimeCommand(language, MOUNT_POINT + "/" + sourceFile));
        return List.copyOf(command);
    }

    /** 语言对应的容器内启动命令。 */
    static List<String> runtimeCommand(CodeLanguage language, String mountedSource) {
        return switch (language) {
            case PYTHON -> List.of("python3", mountedSource);
            case SHELL -> List.of("/bin/sh", mountedSource);
            case JAVA -> List.of("java", mountedSource);
            case JAVASCRIPT -> List.of("node", mountedSource);
            case GO -> List.of("go", "run", mountedSource);
        };
    }

    /**
     * 只在 Docker 守护进程可达且所有配置镜像已存在本地时报告可用。
     *
     * <p>{@code auto} 模式依赖该结果决定是否使用沙箱。如果只检查守护进程，
     * 未缓存镜像会让首次执行隐式访问 Docker Hub，在离线或受限网络中以
     * exit code 125 失败。显式 {@code docker} 模式不调用该方法作启动阻断，
     * 仍允许运维人员选择 Docker 的默认拉取行为。</p>
     */
    private boolean probeDocker() {
        if (!commandSucceeds(List.of(
                "docker", "version", "--format", "{{.Server.Version}}"))) {
            return false;
        }
        List<String> inspect = new ArrayList<>();
        inspect.add("docker");
        inspect.add("image");
        inspect.add("inspect");
        inspect.addAll(images.values().stream().distinct().sorted().toList());
        return commandSucceeds(inspect);
    }

    /** 有界执行 Docker 探测命令，丢弃输出避免管道阻塞。 */
    private static boolean commandSucceeds(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
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
