package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 面向 Web 对话界面的结构化执行事件。
 *
 * <p>由 Runtime 产生的 {@link AgentRunEvent} 在 SSE 边界映射为本事件后实时推送给前端；
 * 其中文件读取、文件编辑与命令执行等状态只能来源于真实的工具执行事件，
 * 不得由模型文本生成，避免 UI 展示虚假的执行记录。</p>
 *
 * @param type 事件类型
 * @param sessionId 会话标识
 * @param message 面向人的简短说明；流式文本时为增量内容
 * @param data 结构化事件数据
 * @param timestamp 事件产生时间
 */
public record ChatStreamEvent(
        Type type,
        String sessionId,
        String message,
        Map<String, Object> data,
        Instant timestamp) {

    /** 创建并复制事件数据。 */
    public ChatStreamEvent {
        type = Objects.requireNonNull(type, "type must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /** 使用当前时间创建事件。 */
    public static ChatStreamEvent of(
            Type type, String sessionId, String message, Map<String, Object> data) {
        return new ChatStreamEvent(type, sessionId, message, data, Instant.now());
    }

    /** 前端按类型选择渲染组件所需的事件分类。 */
    public enum Type {
        /** 模型流式输出的文本增量。 */
        ASSISTANT_MESSAGE,
        /** 工具调用开始，携带真实调用参数。 */
        TOOL_STARTED,
        /** 通用工具调用完成，携带结果摘要。 */
        TOOL_COMPLETED,
        /** 读取了文件，来源于 file_read 工具的真实执行。 */
        FILE_READ,
        /** 编辑了文件，来源于 file_write 工具的真实执行。 */
        FILE_EDITED,
        /** 执行了命令，来源于 run_command 工具的真实执行。 */
        COMMAND_EXECUTED,
        /** 规划、路由、重规划等阶段进展。 */
        PROGRESS,
        /** 运行失败或被取消。 */
        ERROR,
        /** 运行结束时的最终回答。 */
        FINAL_ANSWER
    }
}
