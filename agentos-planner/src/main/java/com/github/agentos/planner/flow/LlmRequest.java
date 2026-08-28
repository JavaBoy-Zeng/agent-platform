package com.github.agentos.planner.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 LLM 调用的请求模型。
 *
 * <p>系统指令与消息序列分离：指令属于 Agent 的稳定身份，消息序列承载
 * 会话历史与当前输入。请求不可变，处理器通过 {@code withXxx} 派生新请求。</p>
 *
 * @param systemInstruction 系统指令；为 {@code null} 表示不发送 system 消息
 * @param messages          从早到晚排列的用户/助手消息，最后一条是当前输入
 * @param model             请求级模型覆盖；为空时使用服务端默认模型
 */
public record LlmRequest(String systemInstruction, List<LlmMessage> messages, String model) {

    /** 兼容使用服务端默认模型的请求构造。 */
    public LlmRequest(String systemInstruction, List<LlmMessage> messages) {
        this(systemInstruction, messages, "");
    }

    /** 创建请求并冻结消息列表。 */
    public LlmRequest {
        messages = List.copyOf(messages);
        model = model == null ? "" : model.trim();
    }

    /** 以单条用户目标作为当前输入创建请求。 */
    public static LlmRequest of(String objective) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        return new LlmRequest(null, List.of(LlmMessage.user(objective)), "");
    }

    /** 返回系统指令。 */
    public Optional<String> instruction() {
        return Optional.ofNullable(systemInstruction)
                .filter(instruction -> !instruction.isBlank());
    }

    /** 派生携带系统指令的新请求；空指令视为清除指令。 */
    public LlmRequest withSystemInstruction(String instruction) {
        return new LlmRequest(
                instruction == null || instruction.isBlank() ? null : instruction, messages, model);
    }

    /** 派生替换全部消息的新请求。 */
    public LlmRequest withMessages(List<LlmMessage> replacement) {
        return new LlmRequest(systemInstruction, replacement, model);
    }

    /** 在消息序列末尾追加一条消息。 */
    public LlmRequest appendMessage(LlmMessage message) {
        List<LlmMessage> expanded = new ArrayList<>(messages);
        expanded.add(message);
        return new LlmRequest(systemInstruction, expanded, model);
    }

    /** 在当前输入之前插入历史消息（保持最后一条为当前输入）。 */
    public LlmRequest prependHistory(List<LlmMessage> history) {
        Objects.requireNonNull(history, "history must not be null");
        if (history.isEmpty()) {
            return this;
        }
        List<LlmMessage> merged = new ArrayList<>(history);
        merged.addAll(messages);
        return new LlmRequest(systemInstruction, merged, model);
    }

    /** 派生请求级模型覆盖；空值恢复为服务端默认模型。 */
    public LlmRequest withModel(String value) {
        return new LlmRequest(systemInstruction, messages, value);
    }
}
