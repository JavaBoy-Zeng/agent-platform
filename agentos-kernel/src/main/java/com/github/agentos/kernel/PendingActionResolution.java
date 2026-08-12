package com.github.agentos.kernel;

import java.util.Map;

/** 外部调用方向 Runtime 提交的挂起动作解决结果。 */
public record PendingActionResolution(
        String pendingActionId, boolean approved, Map<String, Object> data) {

    /** 复制并校验解决结果。 */
    public PendingActionResolution {
        if (pendingActionId == null || pendingActionId.isBlank()) {
            throw new IllegalArgumentException("pendingActionId must not be blank");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 创建审批通过结果。 */
    public static PendingActionResolution approved(String pendingActionId) {
        return new PendingActionResolution(pendingActionId, true, Map.of());
    }

    /** 创建审批拒绝结果。 */
    public static PendingActionResolution rejected(String pendingActionId) {
        return new PendingActionResolution(pendingActionId, false, Map.of());
    }
}
