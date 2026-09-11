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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>接收一个检索目标，先用 {@code browser_search} 发现候选来源，再用
 * {@code web_fetch} 读取不同站点的正文并交给 LLM 整理摘要。目标中包含明确 URL 时，
 * 可跳过搜索直接读取该页面。搜索证据不足时以失败结束，不允许模型猜测 URL 或用
 * 自身知识伪装成检索结果。</p>
 *
 * <p>该 Agent 通过 {@link com.github.agentos.agent.workflow.AgentToolAdapter} 暴露为
 * {@code search-agent} 工具，规划器可按需调用。</p>
 */
public final class SearchAgent extends ResumableSpecialist implements Agent, RoutableAgent {

    /** 注册标识，同时作为 AgentToolAdapter 的工具名。 */
    public static final String ID = "search-agent";

    private static final String SYSTEM_INSTRUCTION = """
            你是信息检索专家。根据用户目标：
            1. 没有明确 URL 时生成一个简洁的搜索关键词
            2. 搜索候选来源并读取不同站点的网页正文；有明确 URL 时直接读取
            3. 只根据提供的有效网页正文整理结构化摘要，并附来源链接
            不得猜测 URL，不得把模型自身知识描述成检索结果，只返回最终摘要。
            """;
    private static final int MIN_VALID_SOURCES = 2;
    private static final int MAX_SOURCES_PER_TOOL = 5;
    private static final int MAX_CONTENT_CHARS_PER_SOURCE = 4_000;
    private static final int MIN_FETCHED_CONTENT_CHARS = 80;
    private static final int MAX_FETCH_CANDIDATES = 5;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 100L;
    private static final Pattern ENTRY_PATTERN = Pattern.compile("^\\d+\\.\\s*(.*)$");
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
            "https?://[^\\s`<>]+", Pattern.CASE_INSENSITIVE);

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAgent.class);

    private final ChatClient chatClient;
    private final LlmFlow llmFlow;
    private final List<AgentTool> searchTools;
    private final AgentTool fetchTool;

    /** 创建信息检索 Agent；searchTools 为按优先级排列的搜索工具，可为空但检索时会报告配置缺失。 */
    public SearchAgent(ChatClient chatClient, List<AgentTool> searchTools) {
        this(chatClient, defaultFlow(), searchTools, null);
    }

    /** 创建具备“搜索后抓取正文”完整链路的信息检索 Agent。 */
    public SearchAgent(ChatClient chatClient, List<AgentTool> searchTools, AgentTool fetchTool) {
        this(chatClient, defaultFlow(), searchTools, fetchTool);
    }

    /** 创建使用自定义请求构造链的检索 Agent。 */
    public SearchAgent(
            ChatClient chatClient,
            LlmFlow llmFlow,
            List<AgentTool> searchTools) {
        this(chatClient, llmFlow, searchTools, null);
    }

    /** 创建使用自定义请求构造链和正文抓取工具的检索 Agent。 */
    public SearchAgent(
            ChatClient chatClient,
            LlmFlow llmFlow,
            List<AgentTool> searchTools,
            AgentTool fetchTool) {
        super(ID, "执行一次完整的网络研究：搜索、读取来源正文并输出带链接摘要。"
                + "成功后不要再重复调用 browser_search 或 web_fetch。");
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.llmFlow = Objects.requireNonNull(llmFlow, "llmFlow must not be null");
        this.searchTools = List.copyOf(Objects.requireNonNull(searchTools, "searchTools must not be null"));
        this.fetchTool = fetchTool;
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
    protected AgentState runWorkflow(
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
        String objective = request.objective();
        String explicitUrl = explicitHttpUrl(objective);
        boolean directFetch = explicitUrl != null && fetchTool != null;
        int plannedToolSteps = directFetch
                ? 1 : searchTools.size() + (fetchTool == null ? 0 : MAX_FETCH_CANDIDATES);
        try {
            eventSink.emit(AgentRunEvent.of(
                    AgentRunEvent.Type.PLAN_CREATED,
                    request.sessionId(),
                    directFetch ? "读取目标网页并整理回答" : "搜索候选来源、读取正文并整理回答",
                    Map.of(
                            "agentId", ID,
                            "planId", planId,
                            "type", "EXECUTION",
                            "origin", "SPECIALIST",
                            "outcome", "CONTINUE",
                            "stepCount", plannedToolSteps,
                            "modelCalls", directFetch ? 1 : 2,
                            "replans", 0)));
            context.throwIfCancelled();
            List<SearchSource> validSources = directFetch
                    ? fetchExplicitUrl(explicitUrl, request, context, eventSink, planId,
                            plannedToolSteps)
                    : searchAndFetchSources(request, context, eventSink, planId,
                            plannedToolSteps);

            StringBuilder contextBuilder = new StringBuilder();
            for (int index = 0; index < validSources.size(); index++) {
                SearchSource source = validSources.get(index);
                contextBuilder.append("来源 ").append(index + 1).append("：")
                        .append(source.url()).append('\n')
                        .append(abbreviate(source.content(), MAX_CONTENT_CHARS_PER_SOURCE))
                        .append("\n\n");
            }

            String summarizePrompt = "只根据以下已读取的网页正文，整理一份关于目标的摘要。"
                    + "包含关键发现并保留来源原始链接；不得补充正文之外的事实。"
                    + (validSources.size() > 1 ? "不同来源说法冲突时请明确指出。" : "") + "\n"
                    + "目标：" + objective + "\n\n" + contextBuilder;
            LlmRequest summarizeRequest = new LlmRequest(SYSTEM_INSTRUCTION,
                    List.of(LlmMessage.user(summarizePrompt))).withRouting(request);
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
                    "已依据有效网页正文整理最终回答",
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
        } catch (Suspended suspended) {
            throw suspended;
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
        if (searchTools.isEmpty()) {
            throw new SearchEvidenceException(
                    "SEARCH_UNAVAILABLE",
                    "搜索工具未配置（browser_search），无法执行多来源检索");
        }
    }

    private List<SearchSource> fetchExplicitUrl(
            String url,
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String planId,
            int plannedToolSteps) {
        ToolResult result = callObservedTool(
                fetchTool, Map.of("url", url), request, context, eventSink,
                planId, "fetch-1", 1, plannedToolSteps, "读取目标网页：" + url);
        if (!result.success()) {
            throw new SearchEvidenceException(
                    "WEB_FETCH_FAILED", "网页读取失败：" + result.error());
        }
        String content = result.output().trim();
        if (content.length() < MIN_FETCHED_CONTENT_CHARS) {
            throw new SearchEvidenceException(
                    "INSUFFICIENT_SEARCH_EVIDENCE", "目标网页没有返回足够的可读正文");
        }
        return List.of(new SearchSource(url, content));
    }

    private List<SearchSource> searchAndFetchSources(
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String planId,
            int plannedToolSteps) {
        requireSearchTools();
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

        Map<String, SearchSource> sourcesByHost = new LinkedHashMap<>();
        List<String> failedTools = new ArrayList<>();
        for (int index = 0; index < searchTools.size(); index++) {
            context.throwIfCancelled();
            AgentTool tool = searchTools.get(index);
            ToolResult searchResult = callObservedTool(tool,
                    Map.of("query", searchQuery, "max_results", MAX_SOURCES_PER_TOOL),
                    request, context, eventSink, planId, "search-" + (index + 1), index + 1,
                    plannedToolSteps, "搜索网络资料：" + searchQuery);
            if (!searchResult.success()) {
                failedTools.add(tool.name() + "：" + searchResult.error());
                continue;
            }
            collectSources(searchResult, sourcesByHost);
        }
        if (sourcesByHost.isEmpty()) {
            if (failedTools.isEmpty()) {
                throw new SearchEvidenceException(
                        "INSUFFICIENT_SEARCH_EVIDENCE", "搜索结果中没有有效条目");
            }
            throw new SearchEvidenceException(
                    "SEARCH_PROVIDER_FAILED", "网络搜索失败：" + String.join("；", failedTools));
        }

        List<SearchSource> candidates = List.copyOf(sourcesByHost.values());
        List<SearchSource> validSources = fetchTool == null
                ? candidates
                : fetchCandidateSources(candidates, request, context, eventSink, planId,
                        plannedToolSteps);
        if (validSources.size() < MIN_VALID_SOURCES) {
            throw new SearchEvidenceException(
                    "INSUFFICIENT_SEARCH_EVIDENCE",
                    "有效来源不足：至少需要 " + MIN_VALID_SOURCES + " 个不同站点，实际获得 "
                            + validSources.size() + " 个");
        }
        return validSources;
    }

    private List<SearchSource> fetchCandidateSources(
            List<SearchSource> candidates,
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String planId,
            int plannedToolSteps) {
        List<SearchSource> fetched = new ArrayList<>();
        int candidateCount = Math.min(candidates.size(), MAX_FETCH_CANDIDATES);
        for (int index = 0; index < candidateCount && fetched.size() < MIN_VALID_SOURCES; index++) {
            SearchSource candidate = candidates.get(index);
            int position = searchTools.size() + index + 1;
            ToolResult result = callObservedTool(
                    fetchTool, Map.of("url", candidate.url()), request, context, eventSink,
                    planId, "fetch-" + (index + 1), position, plannedToolSteps,
                    "读取候选来源：" + candidate.url());
            if (result.success() && result.output().trim().length() >= MIN_FETCHED_CONTENT_CHARS) {
                fetched.add(new SearchSource(candidate.url(), result.output().trim()));
            }
        }
        return List.copyOf(fetched);
    }

    /** 解析搜索工具输出中的结果条目，按站点去重后合并进 sourcesByHost。 */
    private static void collectSources(ToolResult result, Map<String, SearchSource> sourcesByHost) {
        Object structured = result.metadata().get("results");
        if (structured instanceof List<?> entries) {
            int previousSize = sourcesByHost.size();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> values) {
                    addSource(mapText(values, "url"),
                            mapText(values, "title") + " " + mapText(values, "snippet"),
                            sourcesByHost);
                }
            }
            if (sourcesByHost.size() > previousSize) {
                return;
            }
        }
        collectSourcesFromText(result.output(), sourcesByHost);
    }

    private static void collectSourcesFromText(
            String output, Map<String, SearchSource> sourcesByHost) {
        if (output == null || output.isBlank()) {
            return;
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        String[] lines = output.split("\n");
        int index = 0;
        while (index < lines.length) {
            Matcher entryStart = ENTRY_PATTERN.matcher(lines[index].trim());
            if (!entryStart.matches()) {
                index++;
                continue;
            }
            String title = entryStart.group(1).trim();
            String url = "";
            StringBuilder snippet = new StringBuilder();
            index++;
            while (index < lines.length && !ENTRY_PATTERN.matcher(lines[index].trim()).matches()) {
                String line = lines[index].trim();
                if (url.isEmpty() && line.matches("https?://.*")) {
                    url = stripTrailingPunctuation(line);
                } else if (!line.isEmpty()) {
                    snippet.append(line).append(' ');
                }
                index++;
            }
            if (url.isEmpty()) {
                continue;
            }
            if (!seenUrls.add(url)) {
                continue;
            }
            String content = (title + " " + snippet).replaceAll("\\s+", " ").trim();
            addSource(url, content, sourcesByHost);
        }
    }

    private static void addSource(
            String url, String content, Map<String, SearchSource> sourcesByHost) {
        String normalizedUrl = stripTrailingPunctuation(url == null ? "" : url.trim());
        String host = hostOf(normalizedUrl);
        if (host != null) {
            sourcesByHost.putIfAbsent(host, new SearchSource(normalizedUrl, content.trim()));
        }
    }

    private static String mapText(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String explicitHttpUrl(String objective) {
        Matcher matcher = HTTP_URL_PATTERN.matcher(objective == null ? "" : objective);
        if (!matcher.find()) {
            return null;
        }
        String url = stripTrailingPunctuation(matcher.group());
        return hostOf(url) == null ? null : url;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 调用工具；仅对短暂故障和超时进行最多三次指数退避重试。事件标签取自注入工具的实际名称。 */
    private ToolResult callObservedTool(
            AgentTool tool,
            Map<String, Object> arguments,
            AgentRequest request,
            InvocationContext context,
            AgentEventSink eventSink,
            String planId,
            String stepId,
            int position,
            int stepCount,
            String description) {
        String toolName = tool.name();
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.TOOL_STARTED,
                request.sessionId(),
                description,
                Map.of(
                        "agentId", ID,
                        "planId", planId,
                        "stepId", stepId,
                        "toolName", toolName,
                        "arguments", ToolEventSupport.abbreviateArguments(arguments),
                        "position", position,
                        "stepCount", stepCount)));
        ToolResult result;
        int attempts = 0;
        do {
            context.throwIfCancelled();
            attempts++;
            result = callTool(tool, arguments, request, context);
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
                        "arguments", ToolEventSupport.abbreviateArguments(arguments),
                        "status", result.success() ? "COMPLETED" : "FAILED",
                        "success", result.success(),
                        "summary", ToolEventSupport.summarize(result),
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
            AgentTool tool,
            Map<String, Object> arguments,
            AgentRequest request, InvocationContext context) {
        return dispatchTool(tool, new ToolCall(tool.name(), arguments), request, context);
    }

    private String chatWithRetry(
            String sessionId, LlmRequest request, InvocationContext context) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            context.throwIfCancelled();
            try {
                return modelResult("query", context, () -> chatClient.chat(sessionId, request));
            } catch (Suspended suspended) {
            throw suspended;
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
                return modelResult("summary", context, () -> chatClient.chatStream(sessionId, request, delta -> {
                    context.throwIfCancelled();
                    emittedDeltas.incrementAndGet();
                    onDelta.accept(delta);
                }).answer());
            } catch (Suspended suspended) {
            throw suspended;
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
