package com.github.agentos.agent.config;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 由 {@link AgentDefinition} 驱动的通用专家 Agent。
 *
 * <p>行为：以定义中的 instruction 为系统指令，让 LLM 决定是否调用配置的工具，
 * 最终返回 LLM 的回答。如果 {@link AgentDefinition#saveOutput()} 为 true，
 * 还会通过 file_write 工具将输出写入文件。</p>
 *
 * <p>通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 可暴露为工具。</p>
 */
public final class ConfigDrivenAgent extends BaseAgent implements Agent {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigDrivenAgent.class);

    private final AgentDefinition definition;
    private final ChatClient chatClient;
    private final LlmFlow llmFlow;
    private final List<AgentTool> tools;

    /**
     * 创建配置驱动的专家 Agent。
     *
     * @param definition Agent 定义
     * @param chatClient 模型客户端
     * @param tools      可用工具列表（按定义中的工具名解析）
     */
    public ConfigDrivenAgent(
            AgentDefinition definition,
            ChatClient chatClient,
            List<AgentTool> tools) {
        this(definition, chatClient, defaultFlow(definition), tools);
    }

    /** 创建使用自定义请求构造链的配置驱动 Agent。 */
    public ConfigDrivenAgent(
            AgentDefinition definition,
            ChatClient chatClient,
            LlmFlow llmFlow,
            List<AgentTool> tools) {
        super(definition.id(), definition.description(), List.of());
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.llmFlow = Objects.requireNonNull(llmFlow, "llmFlow must not be null");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }

    private static LlmFlow defaultFlow(AgentDefinition def) {
        return new LlmFlow(List.of(
                new InstructionProcessor(def.instruction()),
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
        LOGGER.info("[{}] started sessionId={}", definition.id(), request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", definition.id())));

        try {
            String objective = request.objective();
            StringBuilder contextBuilder = new StringBuilder();

            // 如果有工具可用，让 LLM 决定调用哪个工具
            if (!tools.isEmpty()) {
                LlmRequest planRequest = llmFlow.build(request)
                        .withSystemInstruction(definition.instruction())
                        .withMessages(List.of(LlmMessage.user(
                                "目标：" + objective + "\n\n"
                                + "可用工具：" + tools.stream().map(AgentTool::name).toList() + "\n"
                                + "如果需要使用工具，请只返回工具名（不含参数）；"
                                + "如果不需要工具，请直接返回最终答案。")));
                String decision = chatClient.chat(request.sessionId(), planRequest).trim();

                // 尝试匹配工具名
                AgentTool matched = tools.stream()
                        .filter(t -> decision.toLowerCase().contains(t.name().toLowerCase()))
                        .findFirst().orElse(null);

                if (matched != null) {
                    ToolResult result = callTool(matched, request, context);
                    if (result.success()) {
                        String content = result.output();
                        if (content.length() > 4000) {
                            content = content.substring(0, 4000) + "...(截断)";
                        }
                        contextBuilder.append("工具结果（").append(matched.name()).append("）：\n")
                                .append(content).append("\n\n");
                    }
                }
            }

            // LLM 生成最终回答
            String answerPrompt;
            if (contextBuilder.isEmpty()) {
                answerPrompt = objective;
            } else {
                answerPrompt = "根据以下工具调用结果回答目标。\n"
                        + "目标：" + objective + "\n\n"
                        + contextBuilder;
            }
            LlmRequest answerRequest = new LlmRequest(definition.instruction(),
                    List.of(LlmMessage.user(answerPrompt)));
            String answer = chatClient.chat(request.sessionId(), answerRequest);

            // 如果定义了 saveOutput，写入文件
            if (definition.saveOutput()) {
                AgentTool fileWrite = tools.stream()
                        .filter(t -> "file_write".equals(t.name()))
                        .findFirst().orElse(null);
                if (fileWrite != null) {
                    String filename = definition.id() + "-" + System.currentTimeMillis() + ".md";
                    callToolWithArgs(fileWrite, "file_write",
                            Map.of("path", filename, "content", answer),
                            request, context);
                }
            }

            LOGGER.info("[{}] finished sessionId={} answerChars={} durationMs={}",
                    definition.id(), request.sessionId(), answer.length(),
                    (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    answer,
                    Map.of("agentId", definition.id())));
            return runningState.complete(answer);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[{}] failed sessionId={} error={}",
                    definition.id(), request.sessionId(), message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", definition.id())));
            return runningState.fail(message);
        }
    }

    /** 调用工具（无参数）并返回结果。 */
    private ToolResult callTool(
            AgentTool tool, AgentRequest request, InvocationContext context) {
        return callToolWithArgs(tool, tool.name(), Map.of(), request, context);
    }

    /** 调用工具（带参数）并返回结果。 */
    private ToolResult callToolWithArgs(
            AgentTool tool, String toolName,
            Map<String, Object> arguments,
            AgentRequest request, InvocationContext context) {
        try {
            ToolContext toolContext = new ToolContext(
                    request, context, "", "",
                    AgentExecutionLimits.defaults(), Map.of(), tool);
            return tool.execute(toolContext, new ToolCall(toolName, arguments));
        } catch (Exception exception) {
            LOGGER.warn("[{}] tool {} failed: {}",
                    definition.id(), toolName, exception.getMessage());
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, exception.getMessage());
        }
    }

    /** 返回 Agent 定义。 */
    public AgentDefinition definition() {
        return definition;
    }
}
