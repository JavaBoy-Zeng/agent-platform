package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.ChatStreamEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将 Runtime 产生的 {@link AgentRunEvent} 映射为面向 Web 对话界面的 {@link ChatStreamEvent}。
 *
 * <p>映射规则是纯函数，保证 SSE 边界输出的执行过程事件类型可预期：
 * 流式文本映射为 ASSISTANT_MESSAGE；工具完成事件按真实 toolName 细分为
 * FILE_READ / FILE_EDITED / COMMAND_EXECUTED / TOOL_COMPLETED；运行结束映射为
 * FINAL_ANSWER 或 ERROR。</p>
 *
 * <p>文件读取、命令执行、文件修改状态只能由 TOOL_FINISHED 事件携带的真实 toolName
 * 推导，确保 UI 不展示模型虚构的执行记录。</p>
 */
public final class ChatEventMapper {

    private static final String FILE_READ_TOOL = "file_read";
    private static final String FILE_WRITE_TOOL = "file_write";
    private static final String RUN_COMMAND_TOOL = "run_command";

    private ChatEventMapper() {
    }

    /**
     * 映射单条运行事件；返回 null 表示该事件与对话展示无关（如用量统计、路由拒绝细节），
     * 调用方应直接过滤。
     */
    public static ChatStreamEvent map(AgentRunEvent event) {
        if (event == null) {
            return null;
        }
        return switch (event.type()) {
            case OUTPUT_DELTA -> chat(
                    ChatStreamEvent.Type.ASSISTANT_MESSAGE, event, event.message());
            case TOOL_STARTED -> chat(ChatStreamEvent.Type.TOOL_STARTED, event, event.message());
            case TOOL_FINISHED -> mapToolFinished(event);
            case RUN_COMPLETED -> chat(
                    ChatStreamEvent.Type.FINAL_ANSWER, event, event.message());
            case RUN_FAILED, RUN_CANCELLED -> chat(
                    ChatStreamEvent.Type.ERROR, event, event.message());
            case RUN_STARTED, PLAN_CREATED, ROUTE_DECIDED, REPLAN, DECISION -> chat(
                    ChatStreamEvent.Type.PROGRESS, event, event.message());
            default -> null;
        };
    }

    /** SSE 事件名使用小写蛇形命名，如 {@code assistant_message}、{@code file_read}。 */
    public static String eventName(ChatStreamEvent event) {
        return event.type().name().toLowerCase(Locale.ROOT);
    }

    /** 按真实工具名细分完成事件，未识别的工具统一归入 TOOL_COMPLETED。 */
    private static ChatStreamEvent mapToolFinished(AgentRunEvent event) {
        String toolName = String.valueOf(event.data().getOrDefault("toolName", ""));
        ChatStreamEvent.Type type = switch (toolName) {
            case FILE_READ_TOOL -> ChatStreamEvent.Type.FILE_READ;
            case FILE_WRITE_TOOL -> ChatStreamEvent.Type.FILE_EDITED;
            case RUN_COMMAND_TOOL -> ChatStreamEvent.Type.COMMAND_EXECUTED;
            default -> ChatStreamEvent.Type.TOOL_COMPLETED;
        };
        return chat(type, event, event.message());
    }

    /** 透传原始事件数据并补充来源类型，保留时间戳与顺序语义。 */
    private static ChatStreamEvent chat(
            ChatStreamEvent.Type type, AgentRunEvent event, String message) {
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        data.putIfAbsent("sourceEvent", event.type().name().toLowerCase(Locale.ROOT));
        return new ChatStreamEvent(type, event.sessionId(), message, data, event.occurredAt());
    }
}
