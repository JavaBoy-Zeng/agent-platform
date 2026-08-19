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
import com.github.agentos.planner.flow.LlmFlow;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.planner.flow.InstructionProcessor;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 信息检索专家 Agent。
 *
 * <p>接收一个检索目标，通过 web_search 搜索相关结果，再用 web_fetch 获取详细内容，
 * 最后由 LLM 整理为结构化摘要。如果 web_search 不可用（无 API Key），
 * 退化为 web_fetch + LLM 直答模式。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code search_agent} 工具，规划器可按需调用。</p>
 */
public final class SearchAgent extends BaseAgent implements Agent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "search-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是信息检索专家。根据用户目标：
            1. 生成一个简洁的搜索关键词
            2. 搜索并获取相关网页内容
            3. 整理成结构化摘要（含来源链接）
            只返回最终摘要，不要解释过程。
            """;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAgent.class);

    private final ChatClient chatClient;
    private final LlmFlow llmFlow;
    private final AgentTool webSearchTool;
    private final AgentTool webFetchTool;

    /** 创建信息检索 Agent。 webSearchTool 可为 null（表示无搜索 API）。 */
    public SearchAgent(
            ChatClient chatClient,
            AgentTool webSearchTool,
            AgentTool webFetchTool) {
        this(chatClient, defaultFlow(), webSearchTool, webFetchTool);
    }

    /** 创建使用自定义请求构造链的检索 Agent。 */
    public SearchAgent(
            ChatClient chatClient,
            LlmFlow llmFlow,
            AgentTool webSearchTool,
            AgentTool webFetchTool) {
        super(ID, "Search the web for information and summarize results", List.of());
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.llmFlow = Objects.requireNonNull(llmFlow, "llmFlow must not be null");
        this.webSearchTool = webSearchTool;
        this.webFetchTool = webFetchTool;
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
        LOGGER.info("[search-agent] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID)));

        try {
            String objective = request.objective();

            // 第一步：LLM 生成搜索关键词
            LlmRequest queryRequest = llmFlow.build(request)
                    .withSystemInstruction(SYSTEM_INSTRUCTION)
                    .withMessages(List.of(LlmMessage.user(
                            "根据以下目标生成一个简洁的搜索关键词（只返回关键词本身，不要解释）：\n" + objective)));
            String searchQuery = chatClient.chat(request.sessionId(), queryRequest).trim();

            StringBuilder contextBuilder = new StringBuilder();

            // 第二步：搜索
            if (webSearchTool != null) {
                ToolResult searchResult = callTool(webSearchTool, "web_search",
                        Map.of("query", searchQuery, "max_results", 3),
                        request, context);
                if (searchResult.success()) {
                    contextBuilder.append("搜索结果：\n").append(searchResult.output()).append("\n\n");

                    // 第三步：抓取第一个结果的详细内容
                    if (webFetchTool != null) {
                        String firstUrl = extractFirstUrl(searchResult.output());
                        if (firstUrl != null) {
                            ToolResult fetchResult = callTool(webFetchTool, "web_fetch",
                                    Map.of("url", firstUrl),
                                    request, context);
                            if (fetchResult.success()) {
                                String content = fetchResult.output();
                                if (content.length() > 4000) {
                                    content = content.substring(0, 4000) + "...(截断)";
                                }
                                contextBuilder.append("详细内容（").append(firstUrl).append("）：\n")
                                        .append(content).append("\n\n");
                            }
                        }
                    }
                }
            } else if (webFetchTool != null) {
                // 无搜索 API：让 LLM 生成可能的 URL
                LlmRequest urlRequest = new LlmRequest(SYSTEM_INSTRUCTION, List.of(LlmMessage.user(
                        "为以下目标推荐一个最可能包含答案的完整 URL（只返回 URL，不要解释）：\n" + objective)));
                String url = chatClient.chat(request.sessionId(), urlRequest).trim();
                if (url.startsWith("http")) {
                    ToolResult fetchResult = callTool(webFetchTool, "web_fetch",
                            Map.of("url", url), request, context);
                    if (fetchResult.success()) {
                        String content = fetchResult.output();
                        if (content.length() > 4000) {
                            content = content.substring(0, 4000) + "...(截断)";
                        }
                        contextBuilder.append("网页内容（").append(url).append("）：\n")
                                .append(content).append("\n\n");
                    }
                }
            }

            // 第四步：LLM 整理摘要
            String summarizePrompt;
            if (contextBuilder.isEmpty()) {
                summarizePrompt = "根据你的知识回答以下问题，如果无法回答请明确说明：\n" + objective;
            } else {
                summarizePrompt = "根据以下检索到的信息，整理一份关于以下目标的摘要。"
                        + "包含关键发现和来源链接，如果信息不足请说明：\n"
                        + "目标：" + objective + "\n\n"
                        + contextBuilder;
            }
            LlmRequest summarizeRequest = new LlmRequest(SYSTEM_INSTRUCTION,
                    List.of(LlmMessage.user(summarizePrompt)));
            String answer = chatClient.chat(request.sessionId(), summarizeRequest);

            LOGGER.info("[search-agent] finished sessionId={} answerChars={} durationMs={}",
                    request.sessionId(), answer.length(),
                    (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    answer,
                    Map.of("agentId", ID)));
            return runningState.complete(answer);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[search-agent] failed sessionId={} error={}",
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
            LOGGER.warn("[search-agent] tool {} failed: {}", toolName, exception.getMessage());
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, exception.getMessage());
        }
    }

    /** 从搜索结果文本中提取第一个 URL。 */
    private static String extractFirstUrl(String text) {
        int httpIdx = text.indexOf("http");
        if (httpIdx < 0) return null;
        int end = httpIdx;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                && text.charAt(end) != '"' && text.charAt(end) != ']'
                && text.charAt(end) != ')' && text.charAt(end) != ',') {
            end++;
        }
        return text.substring(httpIdx, end);
    }
}
