package com.github.agentos.tool.api;

import com.github.agentos.kernel.PendingAction;

/** 编排层允许调用的 Agent 委派能力；内部实际操作仍必须独立授权与审批。 */
public interface AgentDelegationTool extends AgentTool {
    /** 拒绝父任务审批时递归清理子任务的恢复数据。 */
    default void discardPending(PendingAction action) { }
}
