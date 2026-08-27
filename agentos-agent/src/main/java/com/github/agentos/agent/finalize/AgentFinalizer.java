package com.github.agentos.agent.finalize;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.planner.AgentPlan;

import java.util.Objects;
import java.util.function.Consumer;

/** Runtime 内部的最终回答收口动作，不作为可被模型调用的外部工具。 */
public interface AgentFinalizer {

    /** 校验并返回 COMPLETE 计划携带的最终回答。 */
    String finish(AgentRequest request, InvocationContext context, AgentPlan completedPlan);

    /**
     * 是否需要为最终回答额外消费一次模型调用预算。
     *
     * <p>纯校验型终结器返回 {@code false}；真正调用模型生成最终文本的流式终结器
     * 必须返回 {@code true}，让 MainAgent 在调用前执行预算检查和记账。</p>
     */
    default boolean requiresModelCall() {
        return false;
    }

    /**
     * 流式生成最终回答。
     *
     * <p>兼容实现先调用同步 {@link #finish}，随后只发送一个完整增量。生产环境的
     * 模型终结器应覆盖此方法，把上游模型 SSE 增量直接传给 {@code onDelta}。</p>
     */
    default String finishStreaming(
            AgentRequest request,
            InvocationContext context,
            AgentPlan completedPlan,
            Consumer<String> onDelta) {
        Objects.requireNonNull(onDelta, "onDelta must not be null");
        String answer = finish(request, context, completedPlan);
        onDelta.accept(answer);
        return answer;
    }
}
