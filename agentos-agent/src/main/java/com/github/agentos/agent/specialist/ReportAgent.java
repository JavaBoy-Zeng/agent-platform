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
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.InstructionProcessor;
import com.github.agentos.planner.flow.LlmFlow;
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
 * 文档生成 Agent。
 *
 * <p>接收报告目标，由 LLM 生成结构化 Markdown 内容并写入文件（自动登记为产物）。
 * 适合生成技术文档、分析报告、会议纪要等结构化文档。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code report_agent} 工具，规划器可按需调用。</p>
 */
public final class ReportAgent extends BaseAgent implements Agent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "report-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是文档撰写专家。根据用户目标生成结构化 Markdown 文档。
            规则：
            1. 使用 Markdown 格式（标题、列表、表格等）
            2. 内容专业、条理清晰
            3. 如果用户提供了上下文信息，基于信息生成；否则基于通用知识生成
            4. 只返回文档内容本身，不要添加额外说明
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportAgent.class);

    private final ChatClient chatClient;
    private final LlmFlow llmFlow;
    private final AgentTool fileWriteTool;

    /** 创建文档生成 Agent。 */
    public ReportAgent(ChatClient chatClient, AgentTool fileWriteTool) {
        this(chatClient, defaultFlow(), fileWriteTool);
    }

    /** 创建使用自定义请求构造链的文档 Agent。 */
    public ReportAgent(ChatClient chatClient, LlmFlow llmFlow, AgentTool fileWriteTool) {
        super(ID, "Generate a structured Markdown report and save it as a file", List.of());
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.llmFlow = Objects.requireNonNull(llmFlow, "llmFlow must not be null");
        this.fileWriteTool = Objects.requireNonNull(fileWriteTool, "fileWriteTool must not be null");
    }

    private static LlmFlow defaultFlow() {
        return new LlmFlow(List.of(
                new InstructionProcessor(SYSTEM_INSTRUCTION),
                new HistoryProcessor()));
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
        LOGGER.info("[report-agent] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID)));

        try {
            // 第一步：LLM 生成文档内容
            LlmRequest generateRequest = llmFlow.build(request)
                    .withSystemInstruction(SYSTEM_INSTRUCTION);
            String content = chatClient.chat(request.sessionId(), generateRequest);

            // 第二步：确定文件名并写入
            String fileName = deriveFileName(request.objective());
            Path filePath = Path.of(System.getProperty("user.dir"), fileName);

            ToolResult writeResult = callTool(fileWriteTool, "file_write",
                    Map.of("path", filePath.toString(),
                            "content", content,
                            "mode", "OVERWRITE",
                            "createParentDirectories", true),
                    request, context);

            if (!writeResult.success()) {
                String error = "文档写入失败: " + writeResult.error();
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.RUN_FAILED,
                        request.sessionId(),
                        error,
                        Map.of("agentId", ID)));
                return runningState.fail(error);
            }

            // 从写入结果中提取产物 ID
            String resultSummary;
            Object data = writeResult.data();
            if (data instanceof Map<?, ?> map && map.get("artifactId") != null) {
                resultSummary = "文档已生成并保存：" + fileName
                        + "（产物 ID: " + map.get("artifactId") + "）";
            } else {
                resultSummary = "文档已生成并保存：" + fileName;
            }

            LOGGER.info("[report-agent] finished sessionId={} file={} durationMs={}",
                    request.sessionId(), fileName,
                    (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    resultSummary,
                    Map.of("agentId", ID, "fileName", fileName)));
            return runningState.complete(resultSummary);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[report-agent] failed sessionId={} error={}",
                    request.sessionId(), message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", ID)));
            return runningState.fail(message);
        }
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
            LOGGER.warn("[report-agent] tool {} failed: {}", toolName, exception.getMessage());
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, exception.getMessage());
        }
    }

    /** 根据目标文本派生一个合理的文件名。 */
    private static String deriveFileName(String objective) {
        String safe = objective.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "_")
                .replaceAll("_+", "_")
                .trim();
        if (safe.length() > 30) {
            safe = safe.substring(0, 30);
        }
        if (safe.isBlank()) {
            safe = "report";
        }
        return safe + "_" + System.currentTimeMillis() + ".md";
    }
}
