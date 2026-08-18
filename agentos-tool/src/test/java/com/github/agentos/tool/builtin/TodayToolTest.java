package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 当前日期工具单元测试。 */
class TodayToolTest {

    // ---------- 星期正确性：用公历已知事实断言，杜绝与实现同源的公式 ----------

    @Test
    void formatsKnownWednesday() {
        // 2026-08-19 是星期三（公历事实，非公式推导）
        assertThat(TodayTool.format(LocalDate.of(2026, 8, 19)))
                .isEqualTo("2026年08月19日 星期三");
    }

    @Test
    void formatsKnownMonday() {
        // 2024-01-01 是星期一
        assertThat(TodayTool.format(LocalDate.of(2024, 1, 1)))
                .isEqualTo("2024年01月01日 星期一");
    }

    @Test
    void formatsKnownSunday() {
        // 2023-01-01 是星期日；旧实现的 getValue()%7 会错算成“星期一”
        assertThat(TodayTool.format(LocalDate.of(2023, 1, 1)))
                .isEqualTo("2023年01月01日 星期日");
    }

    @Test
    void formatsKnownSaturday() {
        // 2024-02-03 是星期六；覆盖周末另一侧
        assertThat(TodayTool.format(LocalDate.of(2024, 2, 3)))
                .isEqualTo("2024年02月03日 星期六");
    }

    // ---------- 时区与元数据 ----------

    @Test
    void respectsProvidedTimezone() {
        // UTC+14 与 UTC-11 相差一天，两个极端时区都应产出合法格式。
        TodayTool kiritimati = new TodayTool(ZoneId.of("Pacific/Kiritimati"));
        TodayTool pagoPago = new TodayTool(ZoneId.of("Pacific/Pago_Pago"));

        String farEast = kiritimati.execute(new ToolCall("current_date", Map.of())).output();
        String farWest = pagoPago.execute(new ToolCall("current_date", Map.of())).output();

        assertThat(farEast).matches("\\d{4}年\\d{2}月\\d{2}日 星期[一二三四五六日]");
        assertThat(farWest).matches("\\d{4}年\\d{2}月\\d{2}日 星期[一二三四五六日]");
    }

    @Test
    void declaresNoParameters() {
        assertThat(new TodayTool().parameters()).isEmpty();
    }

    @Test
    void exposesStableNameForPlanning() {
        assertThat(new TodayTool().name()).isEqualTo("current_date");
        assertThat(new TodayTool().description()).contains("日期");
    }
}
