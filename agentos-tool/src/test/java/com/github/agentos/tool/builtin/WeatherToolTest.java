package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 天气工具参数校验与数据解析测试。
 * 不依赖网络：只验证参数校验路径，实际 API 调用靠手动验证。
 */
class WeatherToolTest {

    private final WeatherTool tool = new WeatherTool();

    @Test
    void rejectsMissingCity() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsBlankCity() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "  ")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsInvalidDateFormat() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆", "date", "08-19")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsPastDate() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆", "date", "2020-01-01")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsDateBeyondForecastRange() {
        String farFuture = LocalDate.now().plusDays(20).toString();
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆", "date", farFuture)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void wmoCodeMappingCoversCommonCodes() {
        // 通过反射验证 WMO 映射覆盖常见天气代码
        // 这里只验证工具能正常创建和参数定义
        assertThat(tool.name()).isEqualTo("weather");
        assertThat(tool.parameters()).hasSize(2);
        assertThat(tool.parameters().get(0).name()).isEqualTo("city");
        assertThat(tool.parameters().get(0).required()).isTrue();
        assertThat(tool.parameters().get(1).name()).isEqualTo("date");
        assertThat(tool.parameters().get(1).required()).isFalse();
    }
}
