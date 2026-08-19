package com.github.agentos.planner;

import com.github.agentos.kernel.ModelUsage;
import com.github.agentos.planner.flow.LlmRequest;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 面向直接问答场景的轻量大语言模型调用协议。
 *
 * <p>与 {@link ModelClient} 的结构化规划输出不同，该接口只做文本问答：
 * 接收 {@link LlmRequest} 携带的系统指令与多轮消息序列，不携带工具定义、
 * 不要求 JSON 输出，用于意图分级后“简单问答”路径的一次性直答。</p>
 */
public interface ChatClient {

    /**
     * 用结构化请求直接生成回答。
     *
     * @param sessionId 会话标识，仅用于日志与链路追踪
     * @param request   系统指令与消息序列
     * @return 模型生成的回答文本
     */
    String chat(String sessionId, LlmRequest request);

    /**
     * 生成回答并携带 token 用量。
     *
     * <p>默认实现退化为 {@link #chat(String, LlmRequest)} 且用量未知；
     * 支持用量解析的客户端应覆盖该方法。</p>
     *
     * @param sessionId 会话标识
     * @param request   系统指令与消息序列
     * @return 回答与用量
     */
    default ChatResponse chatDetails(String sessionId, LlmRequest request) {
        return new ChatResponse(chat(sessionId, request), null);
    }

    /**
     * 流式生成回答，每个增量片段回调 {@code onDelta}。
     *
     * <p>默认实现一次性返回全文（伪流式）；支持 SSE 的客户端应覆盖该方法
     * 实现真实 token 级流式。返回值始终包含完整回答。</p>
     *
     * @param sessionId 会话标识
     * @param request   系统指令与消息序列
     * @param onDelta   增量回调；不得为 {@code null}
     * @return 完整回答与用量
     */
    default ChatResponse chatStream(
            String sessionId, LlmRequest request, Consumer<String> onDelta) {
        Objects.requireNonNull(onDelta, "onDelta must not be null");
        ChatResponse response = chatDetails(sessionId, request);
        onDelta.accept(response.answer());
        return response;
    }

    /** 一次直答回答及其用量。 */
    record ChatResponse(String answer, ModelUsage usage) {
    }
}
