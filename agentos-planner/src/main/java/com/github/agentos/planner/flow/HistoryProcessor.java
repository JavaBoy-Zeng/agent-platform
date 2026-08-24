package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 展开会话历史的处理器。
 *
 * <p>从请求属性读取多行历史文本（“用户：…/助手：…”从早到晚），解析为
 * 真正的 user/assistant 消息序列并插入到当前输入之前，让模型以原生
 * 多轮对话方式解析“那明天呢”这类指代。属性缺失或为空白时零改动。</p>
 */
public final class HistoryProcessor implements LlmRequestProcessor {

    /**
     * {@link AgentRequest#attributes()} 中携带会话历史的键。
     *
     * <p>值为从早到晚排列的“用户/助手”多行文本；服务端在进入运行时前注入。</p>
     */
    public static final String CONVERSATION_HISTORY_ATTRIBUTE = "conversationHistory";

    /**
     * 标记最近会话历史包含文件工具事实的请求属性键。
     *
     * <p>路由器使用该结构化标记识别“那文件里呢”一类省略了路径的
     * 追问，避免它们进入无工具的直答通道。</p>
     */
    public static final String CONVERSATION_FILE_CONTEXT_ATTRIBUTE = "conversationFileContext";

    /** 路由器确认当前轮必须获取新文件证据时使用的请求属性键。 */
    public static final String REQUIRES_FILE_EVIDENCE_ATTRIBUTE = "requiresCurrentFileEvidence";

    private static final String USER_PREFIX = "用户：";
    private static final String ASSISTANT_PREFIX = "助手：";

    private final String historyAttribute;

    /** 创建读取默认属性键 {@link #CONVERSATION_HISTORY_ATTRIBUTE} 的处理器。 */
    public HistoryProcessor() {
        this(CONVERSATION_HISTORY_ATTRIBUTE);
    }

    /** 创建读取指定属性键的处理器。 */
    public HistoryProcessor(String historyAttribute) {
        this.historyAttribute = Objects.requireNonNull(
                historyAttribute, "historyAttribute must not be null");
    }

    @Override
    public LlmRequest process(LlmRequest request, AgentRequest agentRequest) {
        Object history = agentRequest.attributes().get(historyAttribute);
        if (!(history instanceof String text) || text.isBlank()) {
            return request;
        }
        return request.prependHistory(parseHistory(text));
    }

    /**
     * 解析多行历史文本为消息序列。
     *
     * <p>以“用户：”或“助手：”开头的行开启新消息，其余行归属上一条消息
     * （消息正文本身可含换行）。无法识别角色时按用户消息处理。</p>
     */
    static List<LlmMessage> parseHistory(String text) {
        List<LlmMessage> messages = new ArrayList<>();
        StringBuilder current = null;
        LlmMessage.Role currentRole = null;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            LlmMessage.Role role = roleOf(stripped);
            if (role != null) {
                appendMessage(messages, current, currentRole);
                currentRole = role;
                current = new StringBuilder(stripped.substring(prefixLength(stripped)));
            } else if (current != null) {
                current.append('\n').append(stripped);
            }
        }
        appendMessage(messages, current, currentRole);
        return messages;
    }

    private static void appendMessage(
            List<LlmMessage> messages, StringBuilder content,
            LlmMessage.Role role) {
        if (content == null || content.isEmpty() || role == null) {
            return;
        }
        messages.add(new LlmMessage(role, content.toString()));
    }

    private static LlmMessage.Role roleOf(String line) {
        if (line.startsWith(USER_PREFIX)) {
            return LlmMessage.Role.USER;
        }
        if (line.startsWith(ASSISTANT_PREFIX)) {
            return LlmMessage.Role.ASSISTANT;
        }
        return null;
    }

    private static int prefixLength(String line) {
        return line.startsWith(USER_PREFIX)
                ? USER_PREFIX.length()
                : ASSISTANT_PREFIX.length();
    }
}
