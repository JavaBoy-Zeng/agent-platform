package com.github.agentos.tool.builtin.shell;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolActions;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 在受限工作目录内执行单条 shell 命令的工具。
 *
 * <p>安全边界：整体声明为 {@link RiskLevel#HIGH}，默认必须通过人工审批；
 * 是否可按命令内容降级由 HITL 的内容级风险策略决定。执行带超时与输出截断，
 * 防止长时间挂起和 token 爆炸。</p>
 */
public final class RunCommandTool implements AgentTool {

    private final Path workDir;
    private final long defaultTimeoutSeconds;
    private final int maxOutputChars;

    /**
     * 创建 shell 执行工具。
     *
     * @param workDir               允许执行命令的工作目录
     * @param defaultTimeoutSeconds 默认超时秒数
     * @param maxOutputChars        输出截断上限
     */
    public RunCommandTool(Path workDir, long defaultTimeoutSeconds, int maxOutputChars) {
        this.workDir = Objects.requireNonNull(workDir, "workDir must not be null");
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
        return "run_command";
    }

    @Override
    public String description() {
        return "在服务端工作目录执行一条 shell 命令并返回输出。适合运行测试、查看目录、"
                + "执行构建等任务；命令有超时限制，输出过长会被截断。该操作会改动系统状态，"
                + "需要人工审批";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "command", ToolParameter.ValueType.STRING,
                        "要执行的完整 shell 命令", true),
                new ToolParameter(
                        "timeout_seconds", ToolParameter.ValueType.INTEGER,
                        "超时秒数；不填使用默认值", false));
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        String command = requiredString(call.arguments().get("command"), "command");
        long timeout = timeoutSeconds(call.arguments().get("timeout_seconds"));
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder builder = new ProcessBuilder(
                windows ? List.of("cmd", "/c", command) : List.of("/bin/sh", "-c", command));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return ToolResult.failure(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    "failed to start command: " + exception.getMessage());
        }
        String output;
        int exitCode;
        try {
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure(
                        ToolFailureType.TIMEOUT,
                        "command timed out after " + timeout + " seconds: " + abbreviate(command));
            }
            exitCode = process.exitValue();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return ToolResult.failure(
                    ToolFailureType.TIMEOUT, "command execution was interrupted");
        } catch (IOException exception) {
            process.destroyForcibly();
            return ToolResult.failure(
                    ToolFailureType.TOOL_INTERNAL_ERROR,
                    "failed to read command output: " + exception.getMessage());
        }
        String normalized = output.isBlank() ? "(no output)" : output.strip();
        if (exitCode != 0) {
            return ToolResult.failure(
                    ToolFailureType.UNKNOWN,
                    "command exited with code " + exitCode + ": " + truncate(normalized));
        }
        return ToolResult.success(
                truncate(normalized), "", Map.of(
                        "exitCode", exitCode, "command", abbreviate(command)),
                ToolActions.none());
    }

    private long timeoutSeconds(Object configured) {
        if (configured == null) {
            return defaultTimeoutSeconds;
        }
        if (configured instanceof Number number) {
            long value = number.longValue();
            if (value <= 0) {
                throw new IllegalArgumentException("timeout_seconds must be positive");
            }
            return value;
        }
        throw new IllegalArgumentException("timeout_seconds must be an integer");
    }

    private static String requiredString(Object value, String name) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(name + " must be a non-blank string");
    }

    private String truncate(String value) {
        if (value.length() <= maxOutputChars) {
            return value;
        }
        return value.substring(0, maxOutputChars)
                + "\n... (output truncated at " + maxOutputChars + " characters)";
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
