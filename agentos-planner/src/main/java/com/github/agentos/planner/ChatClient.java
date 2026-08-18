package com.github.agentos.planner;

/**
 * 面向直接问答场景的轻量大语言模型调用协议。
 *
 * <p>与 {@link ModelClient} 的结构化规划输出不同，该接口只做单轮文本问答：
 * 不携带工具定义、不要求 JSON 输出、不进入规划循环，用于意图分级后
 * “简单问答”路径的一次性直答。</p>
 */
@FunctionalInterface
public interface ChatClient {

    /**
     * 用单条用户消息直接生成回答。
     *
     * @param sessionId 会话标识，仅用于日志与链路追踪
     * @param userMessage 用户输入
     * @return 模型生成的回答文本
     */
    String chat(String sessionId, String userMessage);
}
