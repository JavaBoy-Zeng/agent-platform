package com.github.agentos.agent.loop;

import com.github.agentos.planner.flow.LlmMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * React 循环的规则式上下文压缩器。
 *
 * <p>无显式计划的 Agent 把全部工具观察都累积在一条消息序列里，长任务很快
 * 会撞上模型上下文窗口。本压缩器以纯规则（零额外模型调用）控制序列体积：</p>
 *
 * <ul>
 *   <li>追加时：单条工具结果先截断到 {@code maxToolResultChars}；</li>
 *   <li>超限时：从最旧的可压缩工具结果开始，压到 {@code compressedChars} 并打上
 *       {@code [compressed]} 标记；</li>
 *   <li>仍超限：从最旧的工具调用对（assistant 请求 + user 结果）开始整对丢弃，
 *       目标消息与<b>最新一轮调用对</b>永不丢弃。</li>
 * </ul>
 *
 * <p>最新一轮调用对（最近一次工具请求 + 结果）是模型对“刚执行了什么、观察到
 * 什么”的唯一记忆，丢弃它会让模型每轮都只看到目标消息，重复发起完全相同的
 * 工具调用（观察丢失型死循环）。即使目标消息本身超预算（如前端注入完整
 * workspace 上下文导致种子消息达数十 KB），也宁可让序列暂时超预算，
 * 也不能让模型失忆。</p>
 *
 * <p>压缩是有损的：被压缩的旧观察只剩摘要性前缀，模型若需要完整细节应重新
 * 调用工具获取。这与业界“上下文满即压缩/丢弃”的主流做法一致。</p>
 */
public final class ConversationCompactor {

    /** 工具结果消息的内容前缀，用于识别可压缩消息。 */
    public static final String TOOL_RESULT_MARKER = "[tool]";

    /** 已压缩消息正文的标记。 */
    public static final String COMPRESSED_MARKER = "[compressed]";

    /**
     * 子代理工具结果的内容前缀：经 {@code AgentToolAdapter} 包装的子 Agent
     * 工具名以 {@code -agent} 结尾（search-agent/code-agent/report-agent）。
     * 这类结果体积通常更大（探索产出），压缩时优先处理以保护主上下文。
     */
    private static final String SUBAGENT_TOOL_PREFIX = "[tool] search-agent";
    private static final String SUBAGENT_TOOL_PREFIX_ALT = "[tool] code-agent";
    private static final String SUBAGENT_TOOL_PREFIX_REPORT = "[tool] report-agent";

    private final int maxTotalChars;
    private final int maxToolResultChars;
    private final int compressedChars;

    /**
     * 创建压缩器。
     *
     * @param maxTotalChars 消息序列总体积上限；超过即触发压缩
     * @param maxToolResultChars 单条工具结果追加时的截断上限
     * @param compressedChars 压缩后单条工具结果保留的字符数
     */
    public ConversationCompactor(
            int maxTotalChars, int maxToolResultChars, int compressedChars) {
        if (maxTotalChars <= 0 || maxToolResultChars <= 0 || compressedChars <= 0) {
            throw new IllegalArgumentException("compactor limits must be positive");
        }
        if (compressedChars >= maxToolResultChars) {
            throw new IllegalArgumentException(
                    "compressedChars must be smaller than maxToolResultChars");
        }
        this.maxTotalChars = maxTotalChars;
        this.maxToolResultChars = maxToolResultChars;
        this.compressedChars = compressedChars;
    }

    /** 单条工具结果追加时的截断上限。 */
    public int maxToolResultChars() {
        return maxToolResultChars;
    }

    /**
     * 把一条原始工具输出封装为可识别、可压缩的工具结果消息内容。
     *
     * @param toolName 工具名称
     * @param output 工具原始输出
     * @return 以 {@code [tool] name} 开头、超长截断的消息内容
     */
    public String wrapToolResult(String toolName, String output) {
        String text = output == null ? "" : output;
        String header = TOOL_RESULT_MARKER + " " + toolName + "\n";
        if (text.length() <= maxToolResultChars) {
            return header + text;
        }
        return header + text.substring(0, maxToolResultChars / 2)
                + "\n…(截断，原文 " + text.length() + " 字符)\n"
                + text.substring(text.length() - (maxToolResultChars - maxToolResultChars / 2));
    }

    /**
     * 压缩消息序列直到回到预算内。
     *
     * @param messages 原消息序列（不会被修改）
     * @return 压缩结果（新序列与统计）
     */
    public Compaction compact(List<LlmMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        List<LlmMessage> working = new ArrayList<>(messages);
        int compressedCount = 0;
        int droppedCount = 0;

        while (totalChars(working) > maxTotalChars) {
            int index = oldestCompressible(working);
            if (index >= 0) {
                working.set(index, compressMessage(working.get(index)));
                compressedCount++;
                continue;
            }
            int pairIndex = oldestDroppablePair(working);
            if (pairIndex < 0) {
                // 目标消息之外无可压缩、无可丢弃内容：已达规则压缩极限。
                break;
            }
            working.remove(pairIndex + 1);
            working.remove(pairIndex);
            droppedCount++;
        }
        return new Compaction(List.copyOf(working), compressedCount, droppedCount);
    }

    /**
     * 序列字符总量（不含首尾空白）。
     */
    public static int totalChars(List<LlmMessage> messages) {
        return messages.stream()
                .mapToInt(message -> message.content().strip().length()
                        + (message.reasoningContent() == null ? 0 : message.reasoningContent().length())
                        + message.toolCalls().stream().mapToInt(call ->
                                call.id().length() + call.name().length() + call.argumentsJson().length()).sum())
                .sum();
    }

    /**
     * 找最旧的可压缩工具结果。
     *
     * <p>优先压缩子代理工具结果（探索产出体积大）：</p>
     * <ol>
     *   <li>第一轮：只找子代理工具结果（{@code search-agent}/{@code code-agent}/{@code report-agent}）</li>
     *   <li>第二轮：找任何普通工具结果</li>
     * </ol>
     */
    private int oldestCompressible(List<LlmMessage> messages) {
        // 第一轮：优先压缩子代理工具结果。
        int subagentIdx = oldestCompressibleMatching(messages, true);
        if (subagentIdx >= 0) {
            return subagentIdx;
        }
        // 第二轮：普通工具结果。
        return oldestCompressibleMatching(messages, false);
    }

    /** 找最旧的可压缩工具结果：{@code subagentOnly=true} 只找子代理工具，否则找普通工具。 */
    private int oldestCompressibleMatching(List<LlmMessage> messages, boolean subagentOnly) {
        int latestPair = latestToolCallPairIndex(messages);
        for (int index = 0; index < messages.size(); index++) {
            if (latestPair >= 0 && index == latestPair + 1) continue;
            LlmMessage message = messages.get(index);
            if (message.role() != LlmMessage.Role.TOOL) {
                continue;
            }
            String content = message.content();
            if (!content.startsWith(TOOL_RESULT_MARKER)) {
                continue;
            }
            if (content.contains(COMPRESSED_MARKER)
                    || content.length() <= compressedChars) {
                continue;
            }
            boolean isSubagent = isSubagentToolResult(content);
            if (subagentOnly == isSubagent) {
                return index;
            }
        }
        return -1;
    }

    /** 判断工具结果是否来自子代理（search-agent/code-agent/report-agent 等）。 */
    private static boolean isSubagentToolResult(String content) {
        return content.startsWith(SUBAGENT_TOOL_PREFIX)
                || content.startsWith(SUBAGENT_TOOL_PREFIX_ALT)
                || content.startsWith(SUBAGENT_TOOL_PREFIX_REPORT)
                || content.startsWith("[tool] workspace-agent")
                || content.startsWith("[tool] utility-agent");
    }

    /**
     * 找最旧的可整对丢弃的工具调用对（assistant 请求 + TOOL 结果）。
     *
     * <p><b>最新一轮调用对永不丢弃</b>：若目标消息本身超预算（如注入了完整
     * workspace 上下文），把唯一调用对也丢掉会让模型每轮只看到目标消息，
     * 重复发起相同工具调用（死循环）。此时宁可序列暂时超预算，也保留最新
     * 观察；更旧的调用对仍可正常丢弃腾出空间。</p>
     */
    private int oldestDroppablePair(List<LlmMessage> messages) {
        int latestPairIndex = latestToolCallPairIndex(messages);
        for (int index = 1; index < latestPairIndex; index++) {
            if (isToolCallPair(messages, index)) {
                return index;
            }
        }
        return -1;
    }

    /** 最新一轮工具调用对（assistant 工具调用 + 相邻 TOOL 结果）的下标；不存在时 -1。 */
    private static int latestToolCallPairIndex(List<LlmMessage> messages) {
        for (int index = messages.size() - 2; index >= 1; index--) {
            if (isToolCallPair(messages, index)) {
                return index;
            }
        }
        return -1;
    }

    /** 下标 index 处是否为工具调用对（assistant 携带 toolCalls 且紧邻 TOOL 结果）。 */
    private static boolean isToolCallPair(List<LlmMessage> messages, int index) {
        return messages.get(index).role() == LlmMessage.Role.ASSISTANT
                && !messages.get(index).toolCalls().isEmpty()
                && messages.get(index + 1).role() == LlmMessage.Role.TOOL;
    }

    private LlmMessage compressMessage(LlmMessage message) {
        String content = message.content();
        String body = content.substring(firstLineEnd(content) + 1);
        String firstLine = content.substring(0, firstLineEnd(content));
        String compressed = body.length() <= compressedChars
                ? body
                : body.substring(0, compressedChars);
        String compressedContent = firstLine + "\n" + COMPRESSED_MARKER + " " + compressed
                + " …(已压缩，原文 " + body.length() + " 字符)";
        if (message.role() == LlmMessage.Role.TOOL) {
            return LlmMessage.toolResult(message.toolCallId(), compressedContent);
        }
        return new LlmMessage(message.role(), compressedContent);
    }

    private static int firstLineEnd(String content) {
        int end = content.indexOf('\n');
        return end < 0 ? content.length() : end;
    }

    /** 一次压缩的结果与统计。 */
    public record Compaction(
            List<LlmMessage> messages,
            int compressedCount,
            int droppedCount) {

        /** 是否发生了任何压缩或丢弃。 */
        public boolean changed() {
            return compressedCount > 0 || droppedCount > 0;
        }
    }
}
