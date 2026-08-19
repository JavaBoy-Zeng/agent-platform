package com.github.agentos.server.history;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话多轮历史服务。
 *
 * <p>从 {@link AgentEventStore} 读取指定会话的领域事件，把每个 Invocation 的
 * {@code AGENT_STARTED}（用户输入）与 {@code AGENT_COMPLETED}（助手输出）
 * 配对成完整轮次，格式化为从早到晚的多行文本。简单问答路径不写 L0 记忆，
 * 事件存储是唯一覆盖全部路由分支（含直答与短路）的会话轨迹来源。</p>
 *
 * <p>输出通过 {@link com.github.agentos.agent.loop.SimpleQaAgent#CONVERSATION_HISTORY_ATTRIBUTE}
 * 注入请求属性：直答路径拼接进 prompt，规划路径随 attributes 进入规划上下文。</p>
 */
public final class SessionHistoryService {

    private final AgentEventStore eventStore;
    private final int maxTurns;
    private final int maxMessageChars;

    /**
     * 创建会话历史服务。
     *
     * @param eventStore 领域事件存储
     * @param maxTurns 最多保留的完整轮次数
     * @param maxMessageChars 单条消息截断上限
     */
    public SessionHistoryService(AgentEventStore eventStore, int maxTurns, int maxMessageChars) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.maxTurns = Math.max(1, maxTurns);
        this.maxMessageChars = Math.max(32, maxMessageChars);
    }

    /**
     * 返回会话的格式化历史；无完整轮次时返回空。
     *
     * @param sessionId 会话标识
     * @return “用户：…/助手：…”多行文本
     */
    public Optional<String> history(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        List<String> lines = formatTurns(eventStore.findBySessionId(sessionId));
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n", lines));
    }

    private List<String> formatTurns(List<AgentEvent> events) {
        List<AgentEvent> ordered = events.stream()
                .sorted(Comparator.comparing(AgentEvent::timestamp))
                .toList();
        Map<String, String> userInputs = new LinkedHashMap<>();
        Map<String, String> assistantOutputs = new LinkedHashMap<>();
        for (AgentEvent event : ordered) {
            if (event.type() == AgentEventType.AGENT_STARTED) {
                userInputs.putIfAbsent(event.invocationId(), event.message());
            } else if (event.type() == AgentEventType.AGENT_COMPLETED) {
                assistantOutputs.putIfAbsent(event.invocationId(), event.message());
            }
        }
        List<String> allTurns = new ArrayList<>();
        for (Map.Entry<String, String> entry : userInputs.entrySet()) {
            String answer = assistantOutputs.get(entry.getKey());
            // 只回放完整轮次：未完成或失败的调用缺少可引用的助手输出。
            if (answer == null || answer.isBlank()) {
                continue;
            }
            allTurns.add("用户：" + truncate(entry.getValue()));
            allTurns.add("助手：" + truncate(answer));
        }
        int fromIndex = Math.max(0, allTurns.size() - maxTurns * 2);
        return allTurns.subList(fromIndex, allTurns.size());
    }

    private String truncate(String message) {
        String text = message == null ? "" : message.strip();
        if (text.length() <= maxMessageChars) {
            return text;
        }
        return text.substring(0, maxMessageChars) + "…";
    }
}
