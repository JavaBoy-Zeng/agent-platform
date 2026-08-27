package com.github.agentos.agent.specialist;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.AgentCapability;
import com.github.agentos.agent.routing.RouteAcceptance;
import com.github.agentos.agent.routing.RouteScope;
import com.github.agentos.agent.routing.RoutableAgent;
import com.github.agentos.agent.routing.SupervisorRouteDecision;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信息检索专家 Agent。
 *
 * <p>接收一个检索目标，通过 web_search 搜索候选来源，再用 web_fetch 获取详细内容。
 * 只有至少两个不同站点的正文通过质量校验后，才会交给 LLM 整理摘要；否则以失败结束，
 * 不允许模型猜测 URL 或用自身知识伪装成检索结果。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code search_agent} 工具，规划器可按需调用。</p>
 */
public final class SearchAgent extends BaseAgent implements Agent, RoutableAgent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "search-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是信息检索专家。根据用户目标：
            1. 生成一个简洁的搜索关键词
            2. 搜索并获取多个独立来源的网页内容
            3. 只根据提供的有效来源整理结构化摘要，并附来源链接
            不得猜测 URL，不得把模型自身知识描述成检索结果，只返回最终摘要。
            """;
    private static final int MIN_VALID_SOURCES = 2;
    private static final int MAX_SOURCE_CANDIDATES = 5;
    private static final int MIN_CONTENT_CHARS = 200;
    private static final int MAX_CONTENT_CHARS_PER_SOURCE = 4_000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 100L;
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^\\s\\\"'<>\\]）)]+", Pattern.CASE_INSENSITIVE);
    private static final List<String> INVALID_CONTENT_MARKERS = List.of(
            "加载中", "请稍候", "验证码", "访问验证", "安全验证", "人机验证",
            "请启用 javascript", "enable javascript", "captcha", "access denied",
            "just a moment", "robot check", "verify you are human");

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAgent.class);

    private final ChatClient chatClient;
    private final LlmFlow llmFlow;
    private final AgentTool webSearchTool;
    private final AgentTool webFetchTool;

    /** 创建信息检索 Agent。webSearchTool 可为 null，但运行检索时会明确报告配置缺失。 */
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
    public Set<AgentCapability> capabilities() {
        return Set.of(AgentCapability.WEB_RESEARCH);
    }

    @Override
    public RouteAcceptance accepts(AgentRequest request, SupervisorRouteDecision decision) {
        if (decision.scope() != RouteScope.EXTERNAL_WORLD) {
            return RouteAcceptance.reject("search-agent only accepts EXTERNAL_WORLD requests");
        }
        if (!decision.requiredCapabilities().contains(AgentCapability.WEB_RESEARCH)) {
            return RouteAcceptance.reject("search-agent requires WEB_RESEARCH intent");
        }
        return RoutableAgent.super.accepts(request, decision);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(eventSink, "eventSink must not be null");
        long started = System.nanoTime();
        LOGGER.info("[search-agent] started sessionId={}", request.sessionId());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED,
                request.sessionId(),
                request.objective(),
                Map.of("agentId", ID)));

        if (isStrongLocalRuntimeQuestion(request.objective())) {
            String message = "LOCAL_RUNTIME_SCOPE_MISMATCH：当前 AgentOS 运行时问题必须读取本地目录";
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.ROUTE_REJECTED,
                    request.sessionId(), message,
                    Map.of(
                            "agentId", ID,
                            "rejectionCode", "LOCAL_RUNTIME_SCOPE_MISMATCH",
                            "scope", RouteScope.LOCAL_RUNTIME.name())));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(), message,
                    Map.of("agentId", ID, "reason", "LOCAL_RUNTIME_SCOPE_MISMATCH")));
            return runningState.fail(message);
        }

        String planId = "search-" + UUID.randomUUID();
        int plannedToolSteps = 1 + MAX_SOURCE_CANDIDATES;
        try {
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.PLAN_CREATED,
                    request.sessionId(),
                    "生成检索词、获取至少两个有效网络来源并整理回答",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "type", "EXECUTION",
                            "origin", "SPECIALIST",
                            "outcome", "CONTINUE",
                            "stepCount", plannedToolSteps,
                            "modelCalls", 2,
                            "replans", 0)));
            requireSearchTools();
            context.throwIfCancelled();

            String objective = request.objective();
            LlmRequest queryRequest = llmFlow.build(request)
                    .withSystemInstruction(SYSTEM_INSTRUCTION)
                    .withMessages(List.of(LlmMessage.user(
                            "根据以下目标生成一个简洁的搜索关键词（只返回关键词本身，不要解释）：\n"
                                    + objective)));
            String searchQuery = chatWithRetry(request.sessionId(), queryRequest, context).trim();
            if (searchQuery.isBlank()) {
                throw new SearchEvidenceException("SEARCH_QUERY_EMPTY", "模型未生成有效搜索关键词");
            }

            ToolResult searchResult = callObservedTool(webSearchTool, "web_search",
                    Map.of("query", searchQuery, "max_results", MAX_SOURCE_CANDIDATES),
                    request, context, eventSink, planId, "search-web", 1,
                    plannedToolSteps, "搜索网络资料：" + searchQuery);
            if (!searchResult.success()) {
                throw new SearchEvidenceException(
                        "SEARCH_PROVIDER_FAILED", "网络搜索失败：" + searchResult.error());
            }

            List<String> candidateUrls = extractDistinctSourceUrls(searchResult.output());
            if (candidateUrls.isEmpty()) {
                throw new SearchEvidenceException(
                        "INSUFFICIENT_SEARCH_EVIDENCE", "搜索结果中没有可抓取的有效 URL");
            }

            List<SearchSource> validSources = new ArrayList<>();
            for (int index = 0;
                 index < candidateUrls.size() && validSources.size() < MIN_VALID_SOURCES;
                 index++) {
                context.throwIfCancelled();
                String url = candidateUrls.get(index);
                String stepId = "fetch-result-" + (index + 1);
                ToolResult fetchResult = callObservedTool(webFetchTool, "web_fetch",
                        Map.of("url", url), request, context, eventSink, planId,
                        stepId, index + 2, plannedToolSteps,
                        "抓取候选来源 " + (index + 1));
                if (!fetchResult.success()) {
                    continue;
                }
                ContentValidation validation = validateFetchedContent(fetchResult.output());
                if (!validation.valid()) {
                    emitRejectedContent(
                            request, eventSink, planId, stepId, url, validation.reason());
                    continue;
                }
                validSources.add(new SearchSource(url, validation.normalizedContent()));
            }

            if (validSources.size() < MIN_VALID_SOURCES) {
                throw new SearchEvidenceException(
                        "INSUFFICIENT_SEARCH_EVIDENCE",
                        "有效来源不足：至少需要 " + MIN_VALID_SOURCES + " 个不同站点，实际获得 "
                                + validSources.size() + " 个");
            }

            StringBuilder contextBuilder = new StringBuilder();
            for (int index = 0; index < validSources.size(); index++) {
                SearchSource source = validSources.get(index);
                contextBuilder.append("来源 ").append(index + 1).append("：")
                        .append(source.url()).append('\n')
                        .append(abbreviate(source.content(), MAX_CONTENT_CHARS_PER_SOURCE))
                        .append("\n\n");
            }

            String summarizePrompt = "只根据以下已经过质量校验的独立来源，整理一份关于目标的摘要。"
                    + "包含关键发现，并保留每个来源的原始链接；不得补充来源之外的事实：\n"
                    + "目标：" + objective + "\n\n" + contextBuilder;
            LlmRequest summarizeRequest = new LlmRequest(SYSTEM_INSTRUCTION,
                    List.of(LlmMessage.user(summarizePrompt)));
            AtomicInteger deltaSequence = new AtomicInteger();
            String answer = chatStreamWithRetry(
                    request.sessionId(), summarizeRequest, context,
                    delta -> eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.OUTPUT_DELTA,
                            request.sessionId(),
                            delta,
                            Map.of(
                                    "agentId", ID,
                                    "sequence", deltaSequence.getAndIncrement(),
                                    "source", "model-sse"))));
            if (answer == null || answer.isBlank()) {
                throw new SearchEvidenceException("SEARCH_SUMMARY_EMPTY", "模型未生成检索摘要");
            }

            LOGGER.info("[search-agent] finished sessionId={} answerChars={} validSources={} durationMs={}",
                    request.sessionId(), answer.length(), validSources.size(),
                    (System.nanoTime() - started) / 1_000_000);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.DECISION,
                    request.sessionId(),
                    "已依据多个有效来源整理最终回答",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "outcome", "COMPLETE",
                            "reason", "SEARCH_SUMMARIZED",
                            "observationCount", validSources.size())));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_COMPLETED,
                    request.sessionId(),
                    answer,
                    Map.of("agentId", ID, "validSourceCount", validSources.size())));
            return runningState.complete(answer);
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            String reason = failureReason(exception);
            LOGGER.warn("[search-agent] failed sessionId={} reason={} error={}",
                    request.sessionId(), reason, message);
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.DECISION,
                    request.sessionId(),
                    "检索任务执行失败",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "outcome", "ABORT",
                            "reason", reason)));
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.RUN_FAILED,
                    request.sessionId(),
                    message,
                    Map.of("agentId", ID, "reason", reason)));
            return runningState.fail(message);
        }
    }

    private static boolean isStrongLocalRuntimeQuestion(String objective) {
        String text = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
        boolean catalogObject = text.contains("工具") || text.contains("tool")
                || text.contains("agent") || text.contains("智能体") || text.contains("skill")
                || text.contains("技能") || text.contains("mcp") || text.contains("模型")
                || text.contains("配置") || text.contains("限制") || text.contains("能力")
                || text.contains("功能");
        boolean localRuntime = text.contains("当前系统") || text.contains("本系统")
                || text.contains("当前平台") || text.contains("本平台")
                || text.contains("当前agentos") || text.contains("本agentos")
                || text.contains("当前 agentos") || text.contains("本 agentos")
                || text.contains("你当前") || text.contains("你有哪些")
                || text.contains("你有什么") || text.contains("你的工具")
                || text.contains("你能调用");
        boolean explicitExternal = text.contains("联网") || text.contains("网上")
                || text.contains("公开资料") || text.contains("agno")
                || text.contains("agentx") || text.contains("http://")
                || text.contains("https://");
        return catalogObject && localRuntime && !explicitExternal;
    }

    private void requireSearchTools() {
        if (webSearchTool == null) {
            throw new SearchEvidenceException(
                    "SEARCH_UNAVAILABLE",
                    "web_search 未配置，无法执行可靠的多来源检索；请配置搜索服务 API Key");
        }
        if (webFetchTool == null) {
            throw new SearchEvidenceException(
                    "SEARCH_UNAVAILABLE", "web_fetch 未配置，无法验证搜索结果正文");
        }
    }

    /** 调用工具；仅对短暂故障和超时进行最多三次指数退避重试。 */
    private ToolResult callObservedTool(
            AgentTool tool,
            String toolName,
            Map<String, Object> arguments,
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String planId,
            String stepId,
            int position,
            int stepCount,
            String description) {
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_STARTED,
                request.sessionId(),
                description,
                Map.of(
                        "agentId", ID,
                        "planId", planId,
                        "stepId", stepId,
                        "toolName", toolName,
                        "position", position,
                        "stepCount", stepCount)));
        ToolResult result;
        int attempts = 0;
        do {
            context.throwIfCancelled();
            attempts++;
            result = callTool(tool, toolName, arguments, request, context);
            if (result.success() || !isRetryable(result.failureType())
                    || attempts >= MAX_RETRY_ATTEMPTS) {
                break;
            }
            LOGGER.warn("[search-agent] retrying tool={} attempt={} failureType={} error={}",
                    toolName, attempts + 1, result.failureType(), result.error());
            pauseBeforeRetry(context, attempts);
        } while (true);

        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_FINISHED,
                request.sessionId(),
                result.success() ? "工具执行完成" : "工具执行失败",
                Map.of(
                        "agentId", ID,
                        "planId", planId,
                        "stepId", stepId,
                        "toolName", toolName,
                        "status", result.success() ? "COMPLETED" : "FAILED",
                        "attempts", attempts)));
        String observation = result.success()
                ? toolName + " 执行成功，返回 " + result.output().length() + " 个字符"
                : toolName + " 执行失败：" + result.error();
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.OBSERVATION,
                request.sessionId(),
                observation,
                Map.of(
                        "agentId", ID,
                        "planId", planId,
                        "stepId", stepId,
                        "toolName", toolName,
                        "status", result.success() ? "COMPLETED" : "FAILED",
                        "failureType", result.failureType().name(),
                        "attempts", attempts)));
        return result;
    }

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
            LOGGER.warn("[search-agent] tool {} failed: {}", toolName, safeMessage(exception));
            return ToolResult.failure(ToolFailureType.TOOL_INTERNAL_ERROR, safeMessage(exception));
        }
    }

    private String chatWithRetry(
            String sessionId, LlmRequest request, InvocationContext context) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            context.throwIfCancelled();
            try {
                return chatClient.chat(sessionId, request);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isTransientModelFailure(exception) || attempt >= MAX_RETRY_ATTEMPTS) {
                    throw exception;
                }
                LOGGER.warn("[search-agent] retrying model request attempt={} error={}",
                        attempt + 1, safeMessage(exception));
                pauseBeforeRetry(context, attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("model request failed") : lastFailure;
    }

    /** 流式请求只有在尚未向下游发送任何字符时才能安全重试，防止重复输出。 */
    private String chatStreamWithRetry(
            String sessionId,
            LlmRequest request,
            InvocationContext context,
            Consumer<String> onDelta) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            context.throwIfCancelled();
            AtomicInteger emittedDeltas = new AtomicInteger();
            try {
                return chatClient.chatStream(sessionId, request, delta -> {
                    context.throwIfCancelled();
                    emittedDeltas.incrementAndGet();
                    onDelta.accept(delta);
                }).answer();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (emittedDeltas.get() > 0 || !isTransientModelFailure(exception)
                        || attempt >= MAX_RETRY_ATTEMPTS) {
                    throw exception;
                }
                LOGGER.warn("[search-agent] retrying streaming model request attempt={} error={}",
                        attempt + 1, safeMessage(exception));
                pauseBeforeRetry(context, attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("streaming model request failed")
                : lastFailure;
    }

    private static boolean isRetryable(ToolFailureType failureType) {
        return failureType == ToolFailureType.TRANSIENT || failureType == ToolFailureType.TIMEOUT;
    }

    private static boolean isTransientModelFailure(Throwable failure) {
        if (failure instanceof CancellationException || Thread.currentThread().isInterrupted()) {
            return false;
        }
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            String message = safeMessage(cursor).toLowerCase(Locale.ROOT);
            if (message.contains("http 429") || message.contains("http 502")
                    || message.contains("http 503") || message.contains("http 504")
                    || message.contains("timeout") || message.contains("timed out")
                    || message.contains("connection reset")
                    || message.contains("temporarily unavailable")
                    || message.contains("service unavailable")
                    || message.contains("bad gateway")
                    || message.contains("gateway timeout")) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(InvocationContext context, int completedAttempts) {
        context.throwIfCancelled();
        long delay = INITIAL_RETRY_DELAY_MILLIS << Math.max(0, completedAttempts - 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("检索重试等待被中断");
        }
        context.throwIfCancelled();
    }

    private static void emitRejectedContent(
            AgentRequest request,
            AgentEventSink eventSink,
            String planId,
            String stepId,
            String url,
            String reason) {
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.OBSERVATION,
                request.sessionId(),
                "候选来源正文无效，继续尝试其他来源：" + reason,
                Map.of(
                        "agentId", ID,
                        "planId", planId,
                        "stepId", stepId,
                        "toolName", "web_fetch",
                        "status", "REJECTED",
                        "failureType", ToolFailureType.INVALID_ARGUMENT.name(),
                        "url", url,
                        "reason", reason)));
    }

    private static ContentValidation validateFetchedContent(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() < MIN_CONTENT_CHARS) {
            return ContentValidation.invalid(
                    "正文仅 " + normalized.length() + " 个字符，少于 " + MIN_CONTENT_CHARS);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String marker : INVALID_CONTENT_MARKERS) {
            if (lower.contains(marker)) {
                return ContentValidation.invalid("正文包含无效页面标记：" + marker);
            }
        }
        return ContentValidation.valid(normalized);
    }

    /** 提取 URL，并按站点去重，确保后续来源相互独立。 */
    private static List<String> extractDistinctSourceUrls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find() && urls.size() < MAX_SOURCE_CANDIDATES) {
            String url = stripTrailingPunctuation(matcher.group());
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if (host != null && !host.isBlank()
                        && hosts.add(host.toLowerCase(Locale.ROOT))) {
                    urls.add(url);
                }
            } catch (IllegalArgumentException ignored) {
                // 搜索服务可能返回格式损坏的链接，跳过并尝试下一个来源。
            }
        }
        return List.copyOf(urls);
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?，。；：！？".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private static String abbreviate(String content, int maxChars) {
        return content.length() <= maxChars
                ? content : content.substring(0, maxChars) + "...(截断)";
    }

    private static String failureReason(RuntimeException exception) {
        if (exception instanceof SearchEvidenceException evidenceException) {
            return evidenceException.reason();
        }
        if (exception instanceof CancellationException) {
            return "SEARCH_CANCELLED";
        }
        return "SEARCH_FAILED";
    }

    private static String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record SearchSource(String url, String content) {
    }

    private record ContentValidation(boolean valid, String normalizedContent, String reason) {
        private static ContentValidation valid(String content) {
            return new ContentValidation(true, content, "");
        }

        private static ContentValidation invalid(String reason) {
            return new ContentValidation(false, "", reason);
        }
    }

    private static final class SearchEvidenceException extends RuntimeException {
        private final String reason;

        private SearchEvidenceException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}
