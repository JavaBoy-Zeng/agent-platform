package com.github.agentos.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 领域事件附带的状态变更指令。
 *
 * <p>事件不只是“发生了什么”的记录，还携带“系统状态应该如何改变”的声明：
 * Runtime 在事件发布时统一消费这些指令（例如把 stateDelta 合并进会话状态），
 * 业务代码不再直接调用 {@code session.setXXX()}。</p>
 *
 * @param stateDelta 需要合并进 {@link SessionState} 的键值增量；空 Map 表示无变更
 * @param transferToAgent 非空时表示控制权应移交给该 Agent；当前作为声明保留
 * @param endInvocation {@code true} 表示本次调用应就此终结
 * @param requireApproval {@code true} 表示事件对应的动作需要人工审批
 */
public record EventActions(
        Map<String, Object> stateDelta,
        String transferToAgent,
        boolean endInvocation,
        boolean requireApproval) {

    /** 无任何指令的空动作。 */
    public static final EventActions NONE = new EventActions(Map.of(), null, false, false);

    /** 创建并校验事件动作。 */
    public EventActions {
        stateDelta = stateDelta == null ? Map.of() : Map.copyOf(stateDelta);
        transferToAgent = transferToAgent == null || transferToAgent.isBlank()
                ? null : transferToAgent.trim();
    }

    /** 创建只包含状态增量的动作。 */
    public static EventActions stateDelta(Map<String, Object> delta) {
        return new EventActions(delta, null, false, false);
    }

    /** 创建需要人工审批的动作。 */
    public static EventActions approval() {
        return new EventActions(Map.of(), null, false, true);
    }

    /** 返回携带额外状态增量的新动作，其余指令保持不变。 */
    public EventActions withStateDelta(Map<String, Object> delta) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(stateDelta);
        merged.putAll(delta == null ? Map.of() : delta);
        return new EventActions(merged, transferToAgent, endInvocation, requireApproval);
    }

    /** 是否携带任一指令。 */
    public boolean isEmpty() {
        return stateDelta.isEmpty() && transferToAgent == null
                && !endInvocation && !requireApproval;
    }
}
