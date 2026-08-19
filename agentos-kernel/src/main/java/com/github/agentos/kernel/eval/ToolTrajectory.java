package com.github.agentos.kernel.eval;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从一次 Invocation 的领域事件流中还原出的工具调用轨迹。
 *
 * <p>Agent 评估的关键不是最终答案，而是“为完成任务走了哪些路径”。
 * 轨迹按时间顺序记录每次工具调用及其结果状态，供
 * {@link ToolTrajectoryEvaluator} 与期望路径比对。</p>
 *
 * @param calls 按开始时间排序的工具调用观察记录
 */
public record ToolTrajectory(List<ToolCall> calls) {

    /** 校验并复制调用列表。 */
    public ToolTrajectory {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** 空轨迹。 */
    public static ToolTrajectory empty() {
        return new ToolTrajectory(List.of());
    }

    /**
     * 从事件流还原轨迹。
     *
     * <p>{@code TOOL_CALL_STARTED} 开启一次调用，随后同名的
     * {@code TOOL_CALL_COMPLETED}/{@code TOOL_CALL_FAILED} 关闭最近一次
     * 同名开启的调用并记录结果状态，兼容并行执行下的事件交错。</p>
     *
     * @param events 单次 Invocation 的完整事件流（时间顺序）
     * @return 还原出的工具调用轨迹
     */
    public static ToolTrajectory fromEvents(List<? extends AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return empty();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (AgentEvent event : events) {
            Map<String, Object> data = event.data();
            String toolName = text(data.get("toolName"));
            switch (event.type()) {
                case TOOL_CALL_STARTED -> {
                    if (toolName != null) {
                        calls.add(new ToolCall(toolName, null, null, event.timestamp()));
                    }
                }
                case TOOL_CALL_COMPLETED, TOOL_CALL_FAILED -> {
                    if (toolName != null) {
                        closeCall(calls, toolName, event, data);
                    }
                }
                default -> {
                    // 与工具轨迹无关的事件类型。
                }
            }
        }
        return new ToolTrajectory(calls);
    }

    private static void closeCall(
            List<ToolCall> calls, String toolName, AgentEvent event, Map<String, Object> data) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            ToolCall call = calls.get(i);
            if (toolName.equals(call.toolName()) && call.pending()) {
                calls.set(i, new ToolCall(
                        toolName,
                        event.type() == AgentEventType.TOOL_CALL_COMPLETED,
                        text(data.get("failureType")),
                        call.timestamp()));
                return;
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 轨迹中的工具名称序列（含重复调用）。 */
    public List<String> toolNames() {
        return calls.stream().map(ToolCall::toolName).toList();
    }

    /** 轨迹中的失败调用数量。 */
    public long failedCount() {
        return calls.stream().filter(call -> Boolean.FALSE.equals(call.success())).count();
    }

    /**
     * 轨迹中的一次工具调用观察。
     *
     * @param toolName 工具名称
     * @param success  是否成功；仅有 STARTED 无终态事件时为 {@code null}（进行中/未知）
     * @param failureType 失败分类；成功或未知时为 {@code null}
     * @param timestamp 调用开始时间
     */
    public record ToolCall(String toolName, Boolean success, String failureType, Instant timestamp) {

        /** 该调用是否尚未观察到终态事件。 */
        public boolean pending() {
            return success == null;
        }
    }
}
