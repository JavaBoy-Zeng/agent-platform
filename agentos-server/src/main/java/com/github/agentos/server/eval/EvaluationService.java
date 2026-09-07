package com.github.agentos.server.eval;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.eval.EvalCase;
import com.github.agentos.kernel.eval.EvaluationResult;
import com.github.agentos.kernel.eval.ToolTrajectoryEvaluator;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 面向 REST 层的评估服务。
 *
 * <p>从 {@link AgentEventStore} 回放单次 Invocation 的完整事件流，
 * 交给 {@link ToolTrajectoryEvaluator} 按用例比对。事件流不存在的
 * Invocation 以空结果返回，由上层转 404。</p>
 */
public class EvaluationService {

    private final AgentEventStore eventStore;
    private final ToolTrajectoryEvaluator evaluator;

    /**
     * 创建评估服务。
     *
     * @param eventStore 领域事件存储
     * @param evaluator  轨迹评估器
     */
    public EvaluationService(AgentEventStore eventStore, ToolTrajectoryEvaluator evaluator) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
    }

    /**
     * 评估一次 Invocation。
     *
     * @param invocationId 被评估的 Invocation 标识
     * @param evalCase     评估用例
     * @return 评估结果；Invocation 无事件记录时返回空
     */
    public Optional<EvaluationResult> evaluate(String invocationId, EvalCase evalCase) {
        List<AgentEvent> events = eventStore.findByInvocationId(invocationId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(evaluator.evaluate(evalCase, invocationId, events));
    }

    /** 返回 Invocation 所属会话，供 REST 层在评估前执行归属校验。 */
    public Optional<String> sessionId(String invocationId) {
        List<AgentEvent> events = eventStore.findByInvocationId(invocationId);
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getFirst().sessionId());
    }
}
