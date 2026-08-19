package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;

/**
 * LLM 请求构造处理器。
 *
 * <p>每个处理器对请求做一次增量变换（注入指令、展开历史、补充记忆等），
 * {@link LlmFlow} 按声明顺序依次应用，最终产出交给
 * {@link com.github.agentos.planner.ChatClient} 的 {@link LlmRequest}。</p>
 */
@FunctionalInterface
public interface LlmRequestProcessor {

    /**
     * 处理当前请求。
     *
     * @param request     上一个处理器产出的请求
     * @param agentRequest 触发本次 LLM 调用的 Agent 请求
     * @return 变换后的请求
     */
    LlmRequest process(LlmRequest request, AgentRequest agentRequest);
}
