package com.github.agentos.agent.routing;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.flow.HistoryProcessor;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 默认启发式意图分类器，纯规则判定、零 LLM 调用。
 *
 * <p>判定优先级：</p>
 * <ol>
 *   <li><b>寒暄短路</b>：去标点归一化后严格命中寒暄/确认白名单，
 *       直接返回 canned answer，不调 LLM、不调工具；</li>
 *   <li><b>简单问答</b>：长度不超过 {@code simpleQaMaxChars} 且不包含任何任务信号词，
 *       派发到装配层注入的 {@code simpleQaAgentId} 对应 Agent 单次直答；</li>
 *   <li><b>复杂任务</b>：其余情况回退到既有 Agent 链路（MainAgent 规划执行）。</li>
 * </ol>
 *
 * <p>任务信号词采用保守黑名单策略：误把简单问答送进 MainAgent 只是多花 token，
 * 结果仍然正确；而误把需要工具的任务送进直答路径会产生幻觉，因此黑名单宁可偏宽。</p>
 */
public final class HeuristicIntentClassifier implements IntentClassifier {

    /** 匹配首尾的空白与 Unicode 标点（含全角问号、感叹号、句号、逗号等）。 */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\s\\p{P}]+|[\\s\\p{P}]+$");

    /** macOS/Linux 绝对路径，例如 {@code /Users/me/resume.md}。 */
    private static final Pattern POSIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])/(?!/)[^\\s/]+(?:/[^\\s/]+)*");

    /** Windows 绝对路径，例如 {@code C:\\Users\\me\\resume.docx}。 */
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])[a-z]:[\\\\/][^\\s]+");

    /** 用户主目录相对路径，例如 {@code ~/notes/report.md}。 */
    private static final Pattern HOME_RELATIVE_PATH = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])~[\\\\/][^\\s]+");

    /**
     * 常见可读文件扩展名；用于识别未带目录的文件引用，例如 {@code 简历.md}。
     * 列表保持显式，避免把普通句号或版本号误判为文件任务。
     */
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            "(?i)[^\\s/\\\\]+\\.(?:md|txt|pdf|docx?|xlsx?|pptx?|csv|tsv|json|ya?ml|xml|"
                    + "properties|java|kt|kts|groovy|js|jsx|ts|tsx|vue|py|go|rs|sql|sh|zsh|html?|css)");

    /** 明确属于问候的归一化字符串集合。 */
    public static final Set<String> trivialGreetings = Set.of(
            "你好", "您好", "嗨", "hi", "hello", "hey", "yo", "哈喽", "哈啰",
            "早上好", "中午好", "下午好", "晚上好", "早安");

    /** 明确属于普通确认的归一化字符串集合。 */
    public static final Set<String> trivialAcknowledgements = Set.of(
            "好的", "好", "嗯", "哦", "噢",
            "ok", "okay",
            "收到", "明白", "了解", "知道了",
            "辛苦了", "麻烦你了", "麻烦您了");

    /** 致谢与告别需要与问候不同的回复语义。 */
    public static final Set<String> trivialThanks = Set.of(
            "谢谢", "感谢", "thanks", "thx", "thank you");
    public static final Set<String> trivialFarewells = Set.of(
            "晚安", "再见", "bye", "goodbye");

    /** 可能是对上一轮问题的回答，有历史时不做无上下文短路。 */
    private static final Set<String> contextualAnswers = Set.of(
            "yes", "y", "no", "n", "是", "不是", "可以", "不可以",
            "好的", "好", "ok", "okay");

    private static final String CONVERSATION_HISTORY_ATTRIBUTE = "conversationHistory";

    private static final Set<String> CATALOG_OBJECT_SIGNALS = Set.of(
            "工具", "tool", "tools", "agent", "agents", "智能体", "skill", "skills",
            "技能", "mcp", "模型", "model", "models", "配置", "限制", "能力", "功能");
    private static final Set<String> STRONG_LOCAL_RUNTIME_SIGNALS = Set.of(
            "当前系统", "本系统", "这个系统", "当前平台", "本平台", "这个平台",
            "当前agentos", "本agentos", "这个agentos", "当前注册", "本机agentos",
            "你当前", "你现在", "你有哪些", "你有什么", "你支持哪些",
            "你的工具", "你的能力", "你能调用", "你可以调用");
    private static final Set<String> EXTERNAL_SCOPE_SIGNALS = Set.of(
            "联网", "网上", "网络搜索", "公开资料", "外部资料", "agno", "agentx",
            "http", "https", "www.");

    /** 需要继承文件事实的常见追问信号。 */
    private static final Set<String> FILE_CONTEXT_FOLLOW_UP_SIGNALS = Set.of(
            "文件", "简历", "文档", "内容", "里面", "其中", "上面", "前面", "刚才",
            "这个", "这些", "这两", "那个", "那些", "它", "哪些", "哪几", "哪家",
            "多少", "多久", "分别", "一共", "是否", "有没有", "有吗", "待过", "经历",
            "公司", "工作", "file", "document", "resume", "above", "those", "these");

    /**
     * 指示请求可能需要工具或实时信息的信号词；命中任意一个即回退到 MainAgent。
     *
     * <p>包含中文动作动词、实时/领域信息词、文件系统与网络信号，以及常见英文
     * 动词。中文按子串、英文按单词边界匹配，均大小写不敏感。列表偏保守：
     * 不确定的一律按复杂任务处理。</p>
     */
    public static final Set<String> taskSignals = Set.of(
            // 中文动作动词
            "帮忙", "麻烦", "查询", "查一下", "查查", "搜索", "检索", "查找", "找一下", "找找",
            "搜一下", "读取", "读一下", "阅读", "查看", "预览", "解析", "写入", "写一下", "创建", "新建", "删除", "删掉",
            "移除", "提交", "推送", "列出", "列一下", "执行", "运行", "调用", "保存", "下载",
            "上传", "发送", "复制", "重命名", "安装", "部署", "打开",
            // 产出落盘：把结果写成文件、放到某个位置，都必须走工具链路
            "写成", "整理成", "导出", "另存", "存到", "存一下", "存成", "放在", "放到",
            "文档", "桌面", "markdown",
            // 实时/领域信息
            "天气", "温度", "气温", "多少度", "下雨", "股价", "股票", "上证", "汇率", "湿度",
            "现在", "当前", "实时", "最新", "最近", "新闻", "资讯", "价格", "行情",
            "几点", "时间", "路况", "堵车", "航班", "赛程", "比分",
            // 日期/时间（直答模型不知道当前日期，必须走工具）
            "今天", "今天几号", "几号", "日期", "星期", "周几", "礼拜",
            "昨天", "明天", "后天", "本月", "这月", "今年", "今年是",
            "date", "today", "yesterday", "tomorrow", "weekday", "what day", "which year", "what year", "current year", "current month", "current date",
            // 文件系统与网络
            "文件", "目录", "文件夹", "路径", "联网", "网上", "公开资料", "外部资料",
            "file", "folder", "directory", "path",
            "http", "https", "www.", "git", "url", "website", "webpage", "curl", "命令", "终端",
            // 英文实时信息
            "now", "current", "latest", "recent", "news", "price", "time", "traffic",
            "flight", "schedule", "score",
            // 常见英文动作动词（按单词/短语边界匹配）
            "weather", "search", "commit", "push", "read", "write", "create", "delete",
            "remove", "list", "run", "execute", "save", "open", "send", "copy", "move",
            "download", "upload", "print", "find",
            // 英文产出落盘
            "export", "desktop");

    private final int maxChars;
    private final int simpleQaMaxChars;
    private final String simpleQaAgentId;
    private final String systemCatalogAgentId;
    private final Map<ResponseKind, String> responses;

    /** 创建默认白名单配置的分类器（简单问答分级关闭，保持旧行为）。 */
    public HeuristicIntentClassifier(int maxChars, String shortCircuitMessage) {
        this(maxChars, shortCircuitMessage, 0, null);
    }

    /**
     * 创建带简单问答分级的分类器。
     *
     * @param maxChars            寒暄短路的文本长度上限
     * @param shortCircuitMessage 寒暄短路时返回的 canned answer
     * @param simpleQaMaxChars    简单问答分级的文本长度上限；{@code <= 0} 表示关闭该分级
     * @param simpleQaAgentId     简单问答分级派发的目标 Agent 标识；分级开启时必填
     */
    public HeuristicIntentClassifier(
            int maxChars, String shortCircuitMessage, int simpleQaMaxChars, String simpleQaAgentId) {
        this(maxChars, simpleQaMaxChars, simpleQaAgentId,
                null, shortCircuitMessage, shortCircuitMessage,
                shortCircuitMessage, shortCircuitMessage);
    }

    /** 创建为不同社交意图配置独立回复的分类器。 */
    public HeuristicIntentClassifier(
            int maxChars,
            int simpleQaMaxChars,
            String simpleQaAgentId,
            String greetingMessage,
            String acknowledgementMessage,
            String thanksMessage,
            String farewellMessage) {
        this(maxChars, simpleQaMaxChars, simpleQaAgentId, null,
                greetingMessage, acknowledgementMessage, thanksMessage, farewellMessage);
    }

    /** 创建带本地运行时目录路由的分类器。 */
    public HeuristicIntentClassifier(
            int maxChars,
            int simpleQaMaxChars,
            String simpleQaAgentId,
            String systemCatalogAgentId,
            String greetingMessage,
            String acknowledgementMessage,
            String thanksMessage,
            String farewellMessage) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must not be negative");
        }
        if (simpleQaMaxChars > 0 && (simpleQaAgentId == null || simpleQaAgentId.isBlank())) {
            throw new IllegalArgumentException(
                    "simpleQaAgentId must not be blank when simpleQaMaxChars > 0");
        }
        this.maxChars = maxChars;
        String acknowledgement = requireMessage(
                acknowledgementMessage, "acknowledgementMessage");
        this.simpleQaMaxChars = simpleQaMaxChars;
        this.simpleQaAgentId = simpleQaAgentId;
        this.systemCatalogAgentId = systemCatalogAgentId == null
                || systemCatalogAgentId.isBlank() ? null : systemCatalogAgentId.trim();
        this.responses = Map.of(
                ResponseKind.GREETING, requireMessage(greetingMessage, "greetingMessage"),
                ResponseKind.ACKNOWLEDGEMENT, acknowledgement,
                ResponseKind.THANKS, requireMessage(thanksMessage, "thanksMessage"),
                ResponseKind.FAREWELL, requireMessage(farewellMessage, "farewellMessage"));
    }

    @Override
    public IntentClassification classify(AgentRequest request, InvocationContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        String normalized = normalize(request.objective());
        ResponseKind responseKind = shortCircuitKind(normalized, request);
        if (responseKind != null) {
            return IntentClassification.shortCircuit(
                    responseKind.intent, responses.get(responseKind));
        }
        if (requiresFileContext(normalized, request)) {
            return new IntentClassification(
                    "file-context-follow-up", null, null, 1.0, null,
                    Map.of(HistoryProcessor.REQUIRES_FILE_EVIDENCE_ATTRIBUTE, true));
        }
        IntentClassification catalog = localRuntimeCatalogIntent(normalized, request);
        if (catalog != null) {
            return catalog;
        }
        if (isSimpleQa(normalized)) {
            return IntentClassification.routeTo(simpleQaAgentId, "simple-qa");
        }
        return IntentClassification.fallback("heuristic-fallback");
    }

    private IntentClassification localRuntimeCatalogIntent(
            String normalized, AgentRequest request) {
        if (systemCatalogAgentId == null || !containsAny(normalized, CATALOG_OBJECT_SIGNALS)) {
            return null;
        }
        if (containsAny(normalized, EXTERNAL_SCOPE_SIGNALS)) {
            return null;
        }
        boolean mentionsAgentOs = normalized.contains("agentos");
        boolean strongLocal = containsAny(normalized, STRONG_LOCAL_RUNTIME_SIGNALS)
                || (mentionsAgentOs && (normalized.contains("现在") || normalized.contains("当前")));
        if (strongLocal || (mentionsAgentOs && historyIdentifiesLocalAgentOs(request))) {
            return IntentClassification.routeTo(systemCatalogAgentId, "system-introspection");
        }
        if (mentionsAgentOs) {
            return IntentClassification.shortCircuit(
                    "system-introspection-clarification",
                    "你指当前运行的 AgentOS，还是网上的同名产品？");
        }
        return null;
    }

    private static boolean historyIdentifiesLocalAgentOs(AgentRequest request) {
        Object history = request.attributes().get(CONVERSATION_HISTORY_ATTRIBUTE);
        if (!(history instanceof String text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("运行在 agentos")
                || normalized.contains("agentos 平台")
                || normalized.contains("当前 agentos");
    }

    private static boolean containsAny(String text, Set<String> signals) {
        return signals.stream().anyMatch(signal -> containsSignal(text, signal));
    }

    private ResponseKind shortCircuitKind(String normalized, AgentRequest request) {
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            return null;
        }
        if (trivialGreetings.contains(normalized)) {
            return ResponseKind.GREETING;
        }
        if (trivialThanks.contains(normalized)) {
            return ResponseKind.THANKS;
        }
        if (trivialFarewells.contains(normalized)) {
            return ResponseKind.FAREWELL;
        }
        if (contextualAnswers.contains(normalized) && hasConversationHistory(request)) {
            return null;
        }
        return trivialAcknowledgements.contains(normalized)
                || contextualAnswers.contains(normalized) ? ResponseKind.ACKNOWLEDGEMENT : null;
    }

    private boolean isSimpleQa(String normalized) {
        if (simpleQaMaxChars <= 0) {
            return false;
        }
        if (normalized.isEmpty() || normalized.length() > simpleQaMaxChars) {
            return false;
        }
        return taskSignals.stream().noneMatch(signal -> containsSignal(normalized, signal))
                && !containsFileReference(normalized);
    }

    /**
     * 文件路径本身就是工具需求的强信号，不能依赖用户恰好使用“读取”这一固定动词。
     * 这也覆盖“看看 /Users/me/a.md”或只粘贴文件名后询问的自然表达。
     */
    private static boolean containsFileReference(String text) {
        return POSIX_ABSOLUTE_PATH.matcher(text).find()
                || WINDOWS_ABSOLUTE_PATH.matcher(text).find()
                || HOME_RELATIVE_PATH.matcher(text).find()
                || FILE_EXTENSION.matcher(text).find();
    }

    private static String normalize(String objective) {
        if (objective == null) {
            return "";
        }
        return EDGE_PUNCTUATION.matcher(objective).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean hasConversationHistory(AgentRequest request) {
        Object history = request.attributes().get(CONVERSATION_HISTORY_ATTRIBUTE);
        return history instanceof String text && !text.isBlank();
    }

    private static boolean requiresFileContext(String normalized, AgentRequest request) {
        if (!Boolean.TRUE.equals(request.attributes().get(
                HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE))) {
            return false;
        }
        return FILE_CONTEXT_FOLLOW_UP_SIGNALS.stream()
                .anyMatch(signal -> containsSignal(normalized, signal));
    }

    /**
     * 中文信号按子串匹配；英文信号按单词/短语边界匹配，避免 thread 命中 read、
     * profile 命中 file 等无关概念问题。
     */
    private static boolean containsSignal(String text, String signal) {
        if (signal.chars().anyMatch(character -> character > 127)) {
            return text.contains(signal);
        }
        int fromIndex = 0;
        while (fromIndex <= text.length() - signal.length()) {
            int index = text.indexOf(signal, fromIndex);
            if (index < 0) {
                return false;
            }
            int end = index + signal.length();
            boolean leftBoundary = !isWordCharacter(signal.charAt(0))
                    || index == 0 || !isWordCharacter(text.charAt(index - 1));
            boolean rightBoundary = !isWordCharacter(signal.charAt(signal.length() - 1))
                    || end == text.length() || !isWordCharacter(text.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static String requireMessage(String message, String field) {
        Objects.requireNonNull(message, field + " must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return message;
    }

    private enum ResponseKind {
        GREETING("trivial-greeting"),
        ACKNOWLEDGEMENT("trivial-acknowledgement"),
        THANKS("trivial-thanks"),
        FAREWELL("trivial-farewell");

        private final String intent;

        ResponseKind(String intent) {
            this.intent = intent;
        }
    }
}
