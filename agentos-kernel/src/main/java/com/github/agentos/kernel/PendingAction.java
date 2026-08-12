package com.github.agentos.kernel;

import java.util.Map;

/** 一次需要在当前执行边界之外完成的挂起动作。 */
public record PendingAction(
        String pendingActionId,
        PendingActionType type,
        String title,
        String description,
        Map<String, Object> payload) {

    /** 复制载荷并校验挂起动作。 */
    public PendingAction {
        if (pendingActionId == null || pendingActionId.isBlank()) {
            throw new IllegalArgumentException("pendingActionId must not be blank");
        }
        type = java.util.Objects.requireNonNull(type, "type must not be null");
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
