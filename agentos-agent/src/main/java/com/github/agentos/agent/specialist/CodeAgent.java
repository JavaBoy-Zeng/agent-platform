package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 代码编写与执行 Agent。
 *
 * <p>接收编程目标，由 LLM 生成代码并写入临时文件，再通过 run_command 执行。
 * 如果执行报错，将错误反馈给 LLM 修复并重试，最多 3 轮。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code code_agent} 工具，规划器可按需调用。</p>
 */
public final class CodeAgent extends BaseAgent implements Agent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "code-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是编程专家。根据用户目标生成可执行的代码。
            规则：
            1. 只返回代码本身，不要用 markdown 代码块包裹
            2. 第一行必须是语言注释：# python / // java / // javascript
            3. 代码必须自包含，不依赖外部文件
            4. 如果需要输出结果，使用 print 或 console.log
            """;

    private static final int MAX_FIX_ATTEMPTS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(CodeAgent.class);

    private final ChatClient chatClient;
    private final AgentTool fileWriteTool;
    private final AgentTool runCommandTool;

    /** 创建代码 Agent。 */
    public CodeAgent(ChatClient chatClient, AgentTool fileWriteTool, AgentTool runCommandTool) {
        super(ID, "Write and execute code, then return the output", List.of());
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.fileWriteTool = Objects.requireNonNull(fileWriteTool, "fileWriteTool must not be null");
        this.runCommandTool = Objects.requireNonNull(runCommandTool, "runCommandTool must not be null");
    }

    @Override
    public AgentExecutionResult run(AgentRequest request, InvocationContext context) {
        AgentState state = run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
        return AgentExecutionResult.from(
                state, context.invocation() == null ? null : context.invocation().pendingAction());
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        long started = System.nanoTime();
        LOGGER.info("[code-agent] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID)));

        try {
            String objective = request.objective();
            String code = generateCode(objective, request.sessionId());
            String extension = detectExtension(code);
            String fileName = "snippet_" + System.currentTimeMillis() + extension;
            Path filePath = Path.of(System.getProperty("java.io.tmpdir"), fileName);

            for (int attempt = 0; attempt < MAX_FIX_ATTEMPTS; attempt++) {
                // 写入文件
                ToolResult writeResult = callTool(fileWriteTool, "file_write",
                        Map.of("path", filePath.toString(),
                                "content", code,
                                "mode", "OVERWRITE",
                                "createParentDirectories", true),
                        request, context);
                if (!writeResult.success()) {
                    LOGGER.warn("[code-agent] file_write failed: {}", writeResult.error());
                    return runningState.fail("无法写入代码文件: " + writeResult.error());
                }

                // 执行
                String command = buildCommand(extension, filePath.toString());
                ToolResult execResult = callTool(runCommandTool, "run_command",
                        Map.of("command", command, "timeout_seconds", 30),
                        request, context);

                if (execResult.success()) {
                    String output = execResult.output().trim();
                    if (output.isEmpty()) {
                        output = "(程序执行完毕，无输出)";
                    }
                    // 截断过长输出
                    if (output.length() > 3000) {
                        output = output.substring(0, 3000) + "\n...(输出截断)";
                    }
                    LOGGER.info("[code-agent] finished sessionId={} attempts={} durationMs={}",
                            request.sessionId(), attempt + 1,
                            (System.nanoTime() - started) / 1_000_000);
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.RUN_COMPLETED,
                            request.sessionId(),
                            output,
                            Map.of("agentId", ID, "attempts", attempt + 1)));
                    return runningState.complete(output);
                }

                // 执行失败，让 LLM 修复
                String error = execResult.error();
                if (error.isBlank()) {
                    error = execResult.output();
                }
                LOGGER.info("[code-agent] attempt {} failed, asking LLM to fix: {}",
                        attempt + 1, error.substring(0, Math.min(error.length(), 200)));

                if (attempt < MAX_FIX_ATTEMPTS - 1) {
                    code = fixCode(objective, code, error, request.sessionId());
                } else {
                    String failure = "代码执行失败（尝试 " + MAX_FIX_ATTEMPTS + " 次）：\n"
                            + "错误：" + error + "\n\n最终代码：\n" + code;
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.RUN_FAILED,
                            request.sessionId(),
                            failure,
                            Map.of("agentId", ID, "attempts", MAX_FIX_ATTEMPTS)));
                    return runningState.fail(failure);
                }
            }

            return runningState.fail("代码执行失败");
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[code-agent] failed sessionId={} error={}",
                    request.sessionId(), message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", ID)));
            return runningState.fail(message);
        }
    }

    /** 让 LLM 生成代码。 */
    private String generateCode(String objective, String sessionId) {
        LlmRequest request = new LlmRequest(SYSTEM_INSTRUCTION,
                List.of(LlmMessage.user(
                        "为以下目标生成可执行代码：\n" + objective)));
        return chatClient.chat(sessionId, request).trim();
    }

    /** 让 LLM 根据错误修复代码。 */
    private String fixCode(String objective, String code, String error, String sessionId) {
        String prompt = """
                编程目标：%s

                之前的代码：
                %s

                执行错误：
                %s

                请修复代码并返回修复后的完整代码（不要用 markdown 代码块包裹）：
                """.formatted(objective, code, error);
        LlmRequest request = new LlmRequest(SYSTEM_INSTRUCTION,
                List.of(LlmMessage.user(prompt)));
        return chatClient.chat(sessionId, request).trim();
    }

    /** 根据代码首行注释检测语言并返回文件扩展名。 */
    private static String detectExtension(String code) {
        String firstLine = code.lines().findFirst().orElse("").toLowerCase().trim();
        if (firstLine.contains("python")) return ".py";
        if (firstLine.contains("java")) return ".java";
        if (firstLine.contains("javascript") || firstLine.contains("node")) return ".js";
        if (firstLine.contains("bash") || firstLine.contains("shell")) return ".sh";
        return ".py";
    }

    /**
     * 根据扩展名构建执行命令。
     *
     * <p>解释器名称随宿主机平台变化：Windows 官方 Python 安装包只提供
     * {@code python}，{@code python3} 命中的是 Microsoft Store 别名占位程序，
     * 不执行脚本；{@code bash} 在 Windows 上同样不保证存在，改由
     * {@code cmd /c} 执行。</p>
     */
    private static String buildCommand(String extension, String filePath) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        return switch (extension) {
            case ".py" -> (windows ? "python " : "python3 ") + filePath;
            case ".js" -> "node " + filePath;
            case ".sh" -> (windows ? "cmd /c " : "bash ") + filePath;
            case ".java" -> "javac " + filePath + " && java -cp "
                    + Path.of(filePath).getParent() + " " + Path.of(filePath).getFileName();
            default -> (windows ? "python " : "python3 ") + filePath;
        };
    }

    /** 调用工具并返回结果。 */
    private ToolResult callTool(
            AgentTool tool, String toolName,
            Map<String, Object> arguments,
            AgentRequest request, InvocationContext context) {
        try {
            ToolContext toolContext = new ToolContext(
                    request, context, "", "",
                    AgentExecutionLimits.defaults(), Map.of(), tool);
            return tool.execute(toolContext, new ToolCall(toolName, arguments));
        } catch (Exception exception) {
            LOGGER.warn("[code-agent] tool {} failed: {}", toolName, exception.getMessage());
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, exception.getMessage());
        }
    }
}
