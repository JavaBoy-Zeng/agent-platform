package com.github.agentos.agent.routing;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentRequest;

import java.util.Locale;
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

    /** 明确属于寒暄的归一化字符串集合。 */
    public static final Set<String> trivialGreetings = Set.of(
            "你好", "您好", "嗨", "hi", "hello", "hey", "yo", "哈喽", "哈啰",
            "早上好", "中午好", "下午好", "晚上好", "早安", "晚安");

    /** 明确属于确认/致谢的归一化字符串集合。 */
    public static final Set<String> trivialAcknowledgements = Set.of(
            "好的", "好", "嗯", "哦", "噢",
            "ok", "okay", "yes", "y", "no", "n",
            "收到", "明白", "了解", "知道了",
            "谢谢", "感谢", "thanks", "thx", "thank you",
            "辛苦了", "麻烦你了", "麻烦您了");

    /**
     * 指示请求可能需要工具或实时信息的信号词；命中任意一个即回退到 MainAgent。
     *
     * <p>包含中文动作动词、实时/领域信息词、文件系统与网络信号，以及常见英文
     * 动词（contains 匹配、大小写不敏感）。列表偏保守：不确定的一律按复杂任务处理。</p>
     */
    public static final Set<String> taskSignals = Set.of(
            // 中文动作动词
            "帮忙", "麻烦", "查询", "查一下", "查查", "搜索", "检索", "查找", "找一下", "找找",
            "搜一下", "读取", "读一下", "写入", "写一下", "创建", "新建", "删除", "删掉",
            "移除", "提交", "推送", "列出", "列一下", "执行", "运行", "调用", "保存", "下载",
            "上传", "发送", "复制", "重命名", "安装", "部署", "打开",
            // 实时/领域信息
            "天气", "温度", "气温", "多少度", "下雨", "股价", "股票", "上证", "汇率", "湿度",
            // 日期/时间（直答模型不知道当前日期，必须走工具）
            "今天", "今天几号", "几号", "日期", "星期", "周几", "礼拜",
            "昨天", "明天", "后天", "本月", "这月", "今年", "今年是",
            "date", "today", "yesterday", "tomorrow", "weekday", "what day", "which year", "what year", "current year", "current month", "current date",
            // 文件系统与网络
            "文件", "目录", "文件夹", "路径", "file", "folder", "directory", "path",
            "http", "www.", "git",
            // 常见英文动作动词（contains 匹配，误伤方向安全）
            "weather", "search", "commit", "push", "read", "write", "create", "delete",
            "remove", "list", "run", "execute", "save", "open", "send", "copy", "move",
            "download", "upload", "print", "find");

    private final int maxChars;
    private final String shortCircuitMessage;
    private final int simpleQaMaxChars;
    private final String simpleQaAgentId;

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
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must not be negative");
        }
        Objects.requireNonNull(shortCircuitMessage, "shortCircuitMessage must not be null");
        if (shortCircuitMessage.isBlank()) {
            throw new IllegalArgumentException("shortCircuitMessage must not be blank");
        }
        if (simpleQaMaxChars > 0 && (simpleQaAgentId == null || simpleQaAgentId.isBlank())) {
            throw new IllegalArgumentException(
                    "simpleQaAgentId must not be blank when simpleQaMaxChars > 0");
        }
        this.maxChars = maxChars;
        this.shortCircuitMessage = shortCircuitMessage;
        this.simpleQaMaxChars = simpleQaMaxChars;
        this.simpleQaAgentId = simpleQaAgentId;
    }

    @Override
    public IntentClassification classify(AgentRequest request, AgentContext context) {
        Objects.requireNonNull(request, "request must not be null");
        String objective = request.objective();
        if (canShortCircuit(objective)) {
            return IntentClassification.shortCircuit(shortCircuitMessage);
        }
        if (isSimpleQa(objective)) {
            return IntentClassification.routeTo(simpleQaAgentId, "simple-qa");
        }
        return IntentClassification.fallback("heuristic-fallback");
    }

    private boolean canShortCircuit(String objective) {
        if (objective == null) {
            return false;
        }
        String normalized = normalize(objective);
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            return false;
        }
        return trivialGreetings.contains(normalized)
                || trivialAcknowledgements.contains(normalized);
    }

    private boolean isSimpleQa(String objective) {
        if (objective == null || simpleQaMaxChars <= 0) {
            return false;
        }
        String normalized = normalize(objective);
        if (normalized.isEmpty() || normalized.length() > simpleQaMaxChars) {
            return false;
        }
        return taskSignals.stream().noneMatch(normalized::contains);
    }

    private static String normalize(String objective) {
        return EDGE_PUNCTUATION.matcher(objective).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }
}
