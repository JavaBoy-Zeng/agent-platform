package com.github.agentos.tool.code;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * 暴露给 Agent 的受控代码执行工具。
 *
 * <p>工具本身只做参数解析与结果格式化，实际执行完全委托给
 * {@link CodeExecutor}。风险等级跟随执行器的隔离程度：沙箱执行器
 * （网络隔离、资源受限）为 {@code LOW}，宿主机直连执行为 {@code HIGH}
 * 并按 HITL 策略走人工审批。</p>
 */
public final class CodeExecutionTool implements AgentTool {

    private final CodeExecutor executor;

    /**
     * 创建代码执行工具。
     *
     * @param executor 底层代码执行器
     */
    public CodeExecutionTool(CodeExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public String name() {
        return "execute_code";
    }

    @Override
    public String description() {
        return "执行一段代码并返回标准输出、标准错误与退出码。支持语言: python、shell、java"
                + "（Java 需定义 public class Main 并提供 main 方法）。当前执行环境: "
                + (executor.isSandboxed()
                        ? "Docker 沙箱（网络隔离、内存与 CPU 受限、源码只读挂载）"
                        : "宿主机本地进程（无沙箱，需要人工审批）");
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "language", ToolParameter.ValueType.STRING,
                        "代码语言: python / shell / java", true),
                new ToolParameter(
                        "code", ToolParameter.ValueType.STRING,
                        "要执行的完整代码", true),
                new ToolParameter(
                        "timeout_seconds", ToolParameter.ValueType.INTEGER,
                        "超时秒数；不填使用默认值", false));
    }

    @Override
    public RiskLevel riskLevel() {
        return executor.isSandboxed() ? RiskLevel.LOW : RiskLevel.HIGH;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        CodeLanguage language;
        try {
            language = CodeLanguage.parse(text(call.arguments().get("language")));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT, exception.getMessage());
        }
        String code = text(call.arguments().get("code"));
        if (code == null || code.isBlank()) {
            return ToolResult.failure(
                    ToolFailureType.INVALID_ARGUMENT, "missing required argument: code");
        }
        if (!executor.supports(language)) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "language not supported by executor " + executor.name()
                            + ": " + language.label());
        }
        CodeExecutionRequest request = new CodeExecutionRequest(
                language, code, timeoutSeconds(call.arguments().get("timeout_seconds")));
        CodeExecutionResult result = executor.execute(request);
        if (result.timedOut()) {
            return ToolResult.failure(
                    ToolFailureType.TIMEOUT,
                    "code execution timed out (executor " + result.executor() + ")");
        }
        if (!result.success()) {
            return ToolResult.failure(ToolFailureType.UNKNOWN,
                    "exit code " + result.exitCode()
                            + renderStreams(result));
        }
        return ToolResult.success(renderOutput(result), "", java.util.Map.of(
                "exitCode", result.exitCode(),
                "durationMillis", result.durationMillis(),
                "executor", result.executor()), com.github.agentos.tool.api.ToolActions.none());
    }

    private static String renderOutput(CodeExecutionResult result) {
        StringBuilder output = new StringBuilder();
        if (!result.stdout().isBlank()) {
            output.append(result.stdout());
        }
        if (!result.stderr().isBlank()) {
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append("[stderr]\n").append(result.stderr());
        }
        return output.isEmpty() ? "(no output)" : output.toString();
    }

    private static String renderStreams(CodeExecutionResult result) {
        StringBuilder output = new StringBuilder();
        if (!result.stdout().isBlank()) {
            output.append("\n[stdout]\n").append(result.stdout());
        }
        if (!result.stderr().isBlank()) {
            output.append("\n[stderr]\n").append(result.stderr());
        }
        return output.toString();
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private static int timeoutSeconds(Object configured) {
        if (configured == null) {
            return 0;
        }
        if (configured instanceof Number number) {
            long value = number.longValue();
            if (value <= 0 || value > 600) {
                throw new IllegalArgumentException(
                        "timeout_seconds must be between 1 and 600");
            }
            return (int) value;
        }
        throw new IllegalArgumentException("timeout_seconds must be an integer");
    }
}
