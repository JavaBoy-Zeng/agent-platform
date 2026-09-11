package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 运行过程中可供日志、SSE 或其他观察端消费的不可变事件。
 *
 * @param type 事件类型
 * @param sessionId 会话标识
 * @param message 面向人的简短说明
 * @param data 结构化事件数据；不得放入无限长度的原始工具输出
 * @param occurredAt 事件产生时间
 */
public record AgentRunEvent(
        Type type,
        String sessionId,
        String message,
        Map<String, Object> data,
        Instant occurredAt) {

    /** 创建并复制事件数据。 */
    public AgentRunEvent {
        type = Objects.requireNonNull(type, "type must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /** 使用当前时间创建事件。 */
    public static AgentRunEvent of(
            Type type, String sessionId, String message, Map<String, Object> data) {
        return new AgentRunEvent(type, sessionId, message, data, Instant.now());
    }

    /** Agent 主链路中的可观察阶段。 */
    public enum Type {
        /** Agent 单次 run 开始；每个会话首次执行时最先发出。 */
        RUN_STARTED,
        /** 主循环已生成执行计划（包含步骤列表）。 */
        PLAN_CREATED,
        /** 工具调用开始：附带工具名与入参。 */
        TOOL_STARTED,
        /** 工具调用结束：附带成功状态与结果摘要。 */
        TOOL_FINISHED,
        /** 工具结果/环境观察回流到主循环，作为下一步推理的输入。 */
        OBSERVATION,
        /** 模型产生的下一步决策（行动、思考或最终回答）。 */
        DECISION,
        /** 路由阶段已选定一个目标 specialist / 子 Agent。 */
        ROUTE_DECIDED,
        /** 路由阶段拒绝当前候选 specialist，需要重新选路。 */
        ROUTE_REJECTED,
        /** 路由阶段判定信息不足，需要向用户发起澄清问询。 */
        ROUTE_CLARIFICATION_REQUIRED,
        /** 主循环判定需要重新生成计划。 */
        REPLAN,
        /** 模型调用的 token 用量与计费计量事件。 */
        USAGE,
        /** 流式输出的增量片段（用于逐 token 推送）。 */
        OUTPUT_DELTA,
        /** Agent run 正常完成，附带最终结果。 */
        RUN_COMPLETED,
        /** Agent run 被外部取消（用户中断或上游超时）。 */
        RUN_CANCELLED,
        /** Agent run 因异常而失败，附带错误信息。 */
        RUN_FAILED
    }
}
