package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.AgentCapability;
import com.github.agentos.agent.routing.RouteAcceptance;
import com.github.agentos.agent.routing.RouteScope;
import com.github.agentos.agent.routing.RoutableAgent;
import com.github.agentos.agent.routing.SupervisorRouteDecision;
import com.github.agentos.agent.workflow.ResumableSpecialist;
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
import com.github.agentos.tool.runtime.ToolEventSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/**
 * 文档生成 Agent。
 *
 * <p>接收报告目标，由 LLM 生成结构化 Markdown 内容并写入文件（自动登记为产物）。
 * 适合生成技术文档、分析报告、会议纪要等结构化文档。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code report_agent} 工具，规划器可按需调用。</p>
 */
public final class ReportAgent extends ResumableSpecialist implements Agent, RoutableAgent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "report-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是文档撰写专家。根据用户目标生成结构化 Markdown 文档。
            规则：
            1. 使用 Markdown 格式（标题、列表、表格等）
            2. 内容专业、条理清晰
            3. 如果用户提供了上下文信息，基于信息生成；否则基于通用知识生成
            4. 只返回文档内容本身，不要添加额外说明
            5. 第一行必须是准确概括主题的一级标题（# 标题），不能使用“顶部引用块”“正文”等结构名称
            6. 不要输出思考过程、生成计划、保存说明，也不要用 ```markdown 包裹整篇文档
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
        super(ID, "Generate a structured Markdown report and save it as a file");
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
    public Set<AgentCapability> capabilities() {
        return Set.of(AgentCapability.DOCUMENT_GENERATION);
    }

    @Override
    public RouteAcceptance accepts(AgentRequest request, SupervisorRouteDecision decision) {
        if (decision.scope() == RouteScope.EXTERNAL_WORLD
                || decision.scope() == RouteScope.LOCAL_RUNTIME) {
            return RouteAcceptance.reject("report-agent requires a document generation task");
        }
        if (!decision.requiredCapabilities().contains(AgentCapability.DOCUMENT_GENERATION)) {
            return RouteAcceptance.reject("report-agent requires DOCUMENT_GENERATION intent");
        }
        return RoutableAgent.super.accepts(request, decision);
    }

    @Override
    protected AgentState runWorkflow(
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

        String planId = "report-" + UUID.randomUUID();
        String writeStepId = "write-document";
        try {
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.PLAN_CREATED,
                    request.sessionId(),
                    "生成结构化 Markdown 文档并登记为会话产物",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "type", "EXECUTION",
                            "origin", "SPECIALIST",
                            "outcome", "CONTINUE",
                            "stepCount", 1,
                            "modelCalls", 1,
                            "replans", 0)));

            // 第一步：LLM 生成文档内容
            LlmRequest generateRequest = llmFlow.build(request)
                    .withSystemInstruction(SYSTEM_INSTRUCTION);
            String rawContent = modelResult("generate", context,
                    () -> chatClient.chat(request.sessionId(), generateRequest));
            GeneratedMarkdownDocument document = GeneratedMarkdownDocument.from(
                    request.objective(), rawContent);

            // 第二步：按文档标题生成可读文件名；同名文件使用递增序号，避免覆盖历史产物。
            Path filePath = Path.of(remember("output-path", () -> nextAvailablePath(
                    Path.of(System.getProperty("user.dir")), document.fileName()).toString()));
            String fileName = filePath.getFileName().toString();

            Map<String, Object> writeArguments = Map.of(
                    "path", filePath.toString(),
                    "content", document.content(),
                    "mode", "CREATE_NEW",
                    "createParentDirectories", true);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.TOOL_STARTED,
                    request.sessionId(),
                    "写入 Markdown 文档 " + fileName,
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "stepId", writeStepId,
                            "toolName", "file_write",
                            "arguments", ToolEventSupport.abbreviateArguments(writeArguments),
                            "position", 1,
                            "stepCount", 1)));
            ToolResult writeResult = callTool(fileWriteTool, "file_write",
                    writeArguments,
                    request, context);

            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.TOOL_FINISHED,
                    request.sessionId(),
                    writeResult.success() ? "工具执行完成" : "工具执行失败",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "stepId", writeStepId,
                            "toolName", "file_write",
                            "arguments", ToolEventSupport.abbreviateArguments(writeArguments),
                            "status", writeResult.success() ? "COMPLETED" : "FAILED",
                            "success", writeResult.success(),
                            "summary", ToolEventSupport.summarize(writeResult),
                            "attempts", 1)));
            if (!writeResult.success()) {
                String error = "文档写入失败: " + writeResult.error();
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.OBSERVATION,
                        request.sessionId(),
                        error,
                        Map.of(
                                "agentId", ID,
                                "planId", planId,
                                "stepId", writeStepId,
                                "toolName", "file_write",
                                "status", "FAILED",
                                "failureType", writeResult.failureType().name(),
                                "attempts", 1)));
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.DECISION,
                        request.sessionId(),
                        "文档写入失败，终止本次运行",
                        Map.of(
                                "agentId", ID,
                                "outcome", "ABORT",
                                "planId", planId,
                                "reason", "TOOL_FAILED")));
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

            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.OBSERVATION,
                    request.sessionId(),
                    resultSummary,
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "stepId", writeStepId,
                            "toolName", "file_write",
                            "status", "COMPLETED",
                            "failureType", "NONE",
                            "attempts", 1)));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.DECISION,
                    request.sessionId(),
                    "文档已经生成并成功登记为产物",
                    Map.of(
                            "agentId", ID,
                            "outcome", "COMPLETE",
                            "planId", planId,
                            "reason", "DOCUMENT_SAVED",
                            "observationCount", 1)));
            LOGGER.info("[report-agent] finished sessionId={} file={} durationMs={}",
                    request.sessionId(), fileName,
                    (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.OUTPUT_DELTA,
                    request.sessionId(),
                    resultSummary,
                    Map.of("agentId", ID, "sequence", 0, "source", "runtime-result")));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    resultSummary,
                    Map.of("agentId", ID, "fileName", fileName)));
            return runningState.complete(resultSummary);
        } catch (Suspended suspended) {
            throw suspended;
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
        return dispatchTool(tool, new ToolCall(toolName, arguments), request, context);
    }

    /** 返回未被占用的输出路径；同名时依次追加 {@code -2}、{@code -3}。 */
    private static Path nextAvailablePath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (Files.notExists(candidate)) {
            return candidate;
        }
        String stem = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3) : fileName;
        for (int suffix = 2; suffix < 10_000; suffix++) {
            candidate = directory.resolve(stem + "-" + suffix + ".md");
            if (Files.notExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法为文档分配可用文件名: " + fileName);
    }
}
