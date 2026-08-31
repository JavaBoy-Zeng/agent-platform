package com.github.agentos.planner.flow;

import com.github.agentos.kernel.AgentRequest;

import java.util.List;
import java.util.Objects;

/**
 * LLM 请求构造链。
 *
 * <p>从当前输入出发，按声明顺序应用 {@link LlmRequestProcessor} 序列，
 * 产出最终请求。处理器顺序即组装顺序：指令 → 历史 → 记忆 → 工具等
 * 增量注入可以独立替换或重排，调用方（如 SimpleQaAgent）不再自行拼 prompt。</p>
 */
public final class LlmFlow {

    private final List<LlmRequestProcessor> processors;

    /** 创建按序应用处理器的 LLM 流。 */
    public LlmFlow(List<LlmRequestProcessor> processors) {
        this.processors = List.copyOf(processors);
        this.processors.forEach(processor -> Objects.requireNonNull(
                processor, "processor must not be null"));
    }

    /** 基于当前 Agent 请求构造 LLM 请求。 */
    public LlmRequest build(AgentRequest agentRequest) {
        Objects.requireNonNull(agentRequest, "agentRequest must not be null");
        LlmRequest request = LlmRequest.of(agentRequest.objective()).withRouting(agentRequest);
        for (LlmRequestProcessor processor : processors) {
            request = processor.process(request, agentRequest);
        }
        return request;
    }
}
