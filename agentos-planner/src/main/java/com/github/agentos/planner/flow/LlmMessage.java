package com.github.agentos.planner.flow;

import java.util.Objects;

/** 一次 LLM 调用中的结构化消息。 */
public record LlmMessage(Role role, String content) {

    /** 消息角色。 */
    public enum Role {
        /** 系统指令。 */
        SYSTEM,
        /** 用户输入。 */
        USER,
        /** 助手输出。 */
        ASSISTANT
    }

    /** 创建消息并校验内容非空。 */
    public LlmMessage {
        Objects.requireNonNull(role, "role must not be null");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        content = content.strip();
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
}
