package com.github.agentos.planner.flow;

import java.util.List;
import java.util.Objects;

/** 一次 LLM 调用中的结构化消息。 */
public record LlmMessage(
        Role role,
        String content,
        List<ToolCallPart> toolCalls,
        String toolCallId) {

    /** 消息角色。 */
    public enum Role {
        /** 系统指令。 */
        SYSTEM,
        /** 用户输入。 */
        USER,
        /** 助手输出。 */
        ASSISTANT,
        /** 工具执行结果（function calling 协议，须与 assistant 的 toolCalls 配对）。 */
        TOOL
    }

    /**
     * assistant 消息携带的原生工具调用（对应 {@code tool_calls[]} 元素）。
     *
     * @param id 厂商返回的调用标识；无标识时由调用方合成，须与 TOOL 结果消息的
     *           {@code toolCallId} 配对
     * @param name 工具名称
     * @param argumentsJson 参数的 JSON 对象文本
     */
    public record ToolCallPart(String id, String name, String argumentsJson) {
    }

    /** 兼容纯文本消息的构造器。 */
    public LlmMessage(Role role, String content) {
        this(role, content, List.of(), null);
    }

    /** 创建消息并校验内容非空、工具调用结构完整。 */
    public LlmMessage {
        Objects.requireNonNull(role, "role must not be null");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        content = content.strip();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("TOOL message requires toolCallId");
        }
    }

    /** 创建用户消息。 */
    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    /** 创建助手消息。 */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    /** 创建系统消息。 */
    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    /** 创建携带原生工具调用的助手消息（content 为模型附带说明，可为占位文本）。 */
    public static LlmMessage assistantToolCall(String content, ToolCallPart call) {
        Objects.requireNonNull(call, "call must not be null");
        return new LlmMessage(Role.ASSISTANT, content, List.of(call), null);
    }

    /** 创建工具执行结果消息，与 assistant 工具调用按 {@code toolCallId} 配对。 */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
