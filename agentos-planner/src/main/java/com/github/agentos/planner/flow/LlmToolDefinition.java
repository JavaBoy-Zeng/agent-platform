package com.github.agentos.planner.flow;

import java.util.Objects;

/**
 * 随请求下发的原生工具定义（function calling 协议）。
 *
 * <p>对应 OpenAI Chat Completions 的 {@code tools[].function} 结构：
 * 名称、说明与 object 类型的 JSON Schema 参数。工具定义在协议层随请求
 * 发给模型，模型经 {@code tool_calls} 返回结构化调用，正文与工具调用
 * 天然分离，无需文本协议解析。</p>
 *
 * @param name 工具唯一名称
 * @param description 面向模型的工具功能说明
 * @param parametersSchema object 类型的 JSON Schema（可为 {@code null} 表示无参数）
 */
public record LlmToolDefinition(
        String name, String description, java.util.Map<String, Object> parametersSchema) {

    /** 创建工具定义并校验名称非空。 */
    public LlmToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }

    /** 创建无参数工具定义。 */
    public static LlmToolDefinition noArgs(String name, String description) {
        return new LlmToolDefinition(name, description, null);
    }
}
