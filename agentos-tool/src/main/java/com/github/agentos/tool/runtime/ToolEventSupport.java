package com.github.agentos.tool.runtime;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为观察端事件构造有界的工具调用参数与结果摘要。
 *
 * <p>事件数据会进入 SSE 流和事件存储，必须截断超长内容
 * （如 file_write 的 content 或命令输出），防止 token 爆炸。</p>
 */
public final class ToolEventSupport {

    private static final int MAX_ARGUMENT_ENTRIES = 8;
    private static final int MAX_ARGUMENT_VALUE_CHARS = 160;
    private static final int MAX_SUMMARY_CHARS = 200;

    private ToolEventSupport() {
    }

    /** 复制工具调用参数并截断每个值，超长值以省略号结尾。 */
    public static Map<String, Object> abbreviateArguments(ToolCall call) {
        return call == null ? new LinkedHashMap<>() : abbreviateArguments(call.arguments());
    }

    /** 复制参数集合并截断每个值，超长值以省略号结尾。 */
    public static Map<String, Object> abbreviateArguments(Map<String, Object> arguments) {
        Map<String, Object> abbreviated = new LinkedHashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return abbreviated;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (count >= MAX_ARGUMENT_ENTRIES) {
                abbreviated.put("…", "(" + (arguments.size() - MAX_ARGUMENT_ENTRIES) + " more)");
                break;
            }
            abbreviated.put(entry.getKey(), abbreviateValue(entry.getValue()));
            count++;
        }
        return abbreviated;
    }

    /** 生成单行结果摘要：成功取输出前缀，失败取错误原因。 */
    public static String summarize(ToolResult result) {
        if (result == null) {
            return "";
        }
        String text = result.success() ? result.output() : result.error();
        return singleLine(text, MAX_SUMMARY_CHARS);
    }

    private static Object abbreviateValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return singleLine(String.valueOf(value), MAX_ARGUMENT_VALUE_CHARS);
    }

    private static String singleLine(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= maxChars
                ? singleLine
                : singleLine.substring(0, maxChars) + "…";
    }
}
