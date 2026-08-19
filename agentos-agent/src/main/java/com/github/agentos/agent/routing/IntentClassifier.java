package com.github.agentos.agent.routing;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentRequest;

/** 在请求进入 MainAgent 之前产出路由决策的策略接口。 */
@FunctionalInterface
public interface IntentClassifier {

    /**
     * 对单次请求进行意图判定，输出路由决策。
     *
     * <p>实现应当保持轻量与无副作用，避免在分类阶段触发模型调用、工具调用或记忆写入。
     * 复杂的语义判定应当委托给 {@code fallback}，由真正的 Agent 链路完成。</p>
     *
     * @param request 本次用户请求
     * @param context 本次身份和任务上下文
     * @return 路由决策，不可为 {@code null}
     */
    IntentClassification classify(AgentRequest request, InvocationContext context);
}