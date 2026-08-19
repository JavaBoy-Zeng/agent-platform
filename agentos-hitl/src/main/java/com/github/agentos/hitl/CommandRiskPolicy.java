package com.github.agentos.hitl;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按命令内容判定风险的 {@link RiskPolicy} 扩展。
 *
 * <p>对 {@code run_command} 工具做前缀级白名单判定：仅首词命中只读命令白名单、
 * 且不含 shell 控制字符的命令可免审批直接执行；其余命令一律需要人工审批。
 * 其他工具仍按工具级风险等级判定。</p>
 *
 * <p>安全取向保守：白名单外、含管道/重定向/命令串联/命令替换的命令全部要求审批，
 * 避免 “cat file; rm -rf /” 这类以只读命令开头的拼接攻击绕过审批。</p>
 */
public final class CommandRiskPolicy extends RiskPolicy {

    /** 免审批命令白名单的默认值：只读查看类命令与安全构建命令。 */
    public static final Set<String> DEFAULT_LOW_RISK_COMMANDS = Set.of(
            "ls", "pwd", "cat", "head", "tail", "grep", "find", "wc", "date", "whoami",
            "echo", "which", "du", "df", "env", "printenv",
            "git status", "git log", "git diff", "git show", "git branch", "git blame",
            "mvn -v", "mvn test", "mvn verify",
            "java -version", "node -v", "npm -v", "python --version", "python3 --version");

    /** 出现即要求审批的 shell 控制字符与替换语法。 */
    private static final String SHELL_METACHARACTERS = ";|&><`$\n\r\\";

    private final Set<String> lowRiskCommands;

    /** 使用默认白名单创建内容级风险策略。 */
    public CommandRiskPolicy(AgentTool.RiskLevel approvalThreshold) {
        this(approvalThreshold, DEFAULT_LOW_RISK_COMMANDS);
    }

    /**
     * 使用自定义白名单创建内容级风险策略。
     *
     * @param lowRiskCommands 免审批的命令前缀集合
     */
    public CommandRiskPolicy(
            AgentTool.RiskLevel approvalThreshold, List<String> lowRiskCommands) {
        this(approvalThreshold,
                Objects.requireNonNull(lowRiskCommands, "lowRiskCommands must not be null")
                        .stream().map(CommandRiskPolicy::normalize).collect(Collectors.toSet()));
    }

    private CommandRiskPolicy(
            AgentTool.RiskLevel approvalThreshold, Set<String> lowRiskCommands) {
        super(approvalThreshold);
        this.lowRiskCommands = Set.copyOf(lowRiskCommands);
    }

    /** run_command 按命令文本判定，其余工具沿用工具级等级判定。 */
    @Override
    public boolean requiresApproval(InvocationContext context, AgentTool tool, ToolCall call) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(call, "call must not be null");
        if (!"run_command".equals(tool.name())) {
            return super.requiresApproval(context, tool, call);
        }
        return !isLowRiskCommand(call.arguments().get("command"));
    }

    /** 判定命令是否可免审批：白名单前缀命中且不含 shell 控制字符。 */
    boolean isLowRiskCommand(Object command) {
        if (!(command instanceof String text) || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        if (normalized.chars().anyMatch(ch -> SHELL_METACHARACTERS.indexOf(ch) >= 0)) {
            return false;
        }
        return lowRiskCommands.stream().anyMatch(prefix ->
                normalized.equals(prefix) || normalized.startsWith(prefix + " "));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
