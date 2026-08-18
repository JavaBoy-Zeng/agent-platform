package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 当前日期查询工具。
 *
 * <p>返回运行环境当前时区的日期，供规划器在用户询问“今天是哪年哪月哪日”、
 * “今天几号”等需要真实日期的问题时调用。纯只读操作，低风险。</p>
 */
public final class TodayTool implements AgentTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy年MM月dd日")
            .localizedBy(Locale.CHINESE);

    private final ZoneId zoneId;

    /** 使用系统默认时区创建日期工具。 */
    public TodayTool() {
        this(ZoneId.systemDefault());
    }

    /** 使用指定时区创建日期工具。 */
    public TodayTool(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    /** 获取工具名称。 */
    @Override
    public String name() {
        return "current_date";
    }

    /** 获取工具描述。 */
    @Override
    public String description() {
        return "获取今天的日期，包括年、月、日和星期";
    }

    /** 该工具不接收参数。 */
    @Override
    public List<ToolParameter> parameters() {
        return List.of();
    }

    /** 返回当前日期。 */
    @Override
    public ToolResult execute(ToolCall call) {
        return ToolResult.success(format(LocalDate.now(zoneId)));
    }

    /** 格式化日期为 “yyyy年MM月dd日 星期X”；星期直接取 JDK 本地化名称，避免手工映射错位。 */
    static String format(LocalDate date) {
        return date.format(FORMATTER) + " "
                + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
    }
}
