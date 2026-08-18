package com.github.agentos.agent.routing;

import com.github.agentos.kernel.ExecutionMode;

import java.util.Map;
import java.util.Objects;

/**
 * 意图识别层的不可变路由决策。
 *
 * <p>通过组合下列字段表达一次路由结果：</p>
 * <ul>
 *   <li>{@code directAnswer != null} ⇒ 短路返回，不调 LLM、不调工具；</li>
 *   <li>{@code agentId != null} ⇒ 通过 {@code AgentRegistry} 派发到具体 Agent；</li>
 *   <li>否则 ⇒ 落到 fallback 路径，沿用既有执行链路。</li>
 * </ul>
 *
 * @param intent         人类可读的意图标签，例如 {@code "trivial-qa"}、{@code "agent:researcher"}
 * @param agentId        AgentRegistry 中注册的 Agent 标识；为 {@code null} 时表示走 fallback
 * @param executionMode  指定的执行模式；为 {@code null} 时由所选 Agent 自己决定
 * @param confidence     分类置信度，取值范围 [0.0, 1.0]；可空
 * @param directAnswer   非 {@code null} 时直接返回终态，跳过 LLM 调用
 * @param attributes     调用方可在后续阶段读取的扩展属性
 */
public record IntentClassification(
        String intent,
        String agentId,
        ExecutionMode executionMode,
        Double confidence,
        String directAnswer,
        Map<String, Object> attributes) {

    /** 规范化可选字段并校验必填项。 */
    public IntentClassification {
        Objects.requireNonNull(intent, "intent must not be null");
        if (intent.isBlank()) {
            throw new IllegalArgumentException("intent must not be blank");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be within [0.0, 1.0]");
        }
    }

    /** 构造短路决策，直接返回 canned answer，不调 LLM。 */
    public static IntentClassification shortCircuit(String answer) {
        return new IntentClassification("trivial-qa", null, null, 1.0, answer, Map.of());
    }

    /** 构造派发到指定 Agent 的路由决策。 */
    public static IntentClassification routeTo(String agentId, String intent) {
        return new IntentClassification(intent, agentId, null, 0.8, null, Map.of());
    }

    /** 构造回退到既有执行链路的决策（默认行为）。 */
    public static IntentClassification fallback(String intent) {
        return new IntentClassification(intent, null, null, 0.0, null, Map.of());
    }

    /** 是否应当跳过 LLM 直接返回。 */
    public boolean isShortCircuit() {
        return directAnswer != null;
    }

    /** 是否要求派发到指定 Agent。 */
    public boolean hasAgentTarget() {
        return agentId != null;
    }
}