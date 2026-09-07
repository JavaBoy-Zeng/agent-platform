package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.other.WeatherTool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 天气工具参数校验与数据解析测试。
 * 解析路径通过本地 HttpServer mock 验证，避免依赖真实网络。
 */
class WeatherToolTest {

    private WeatherTool tool;
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/geocode", exchange -> {
            byte[] body = """
                    {"results":[
                      {"latitude":29.56063,"longitude":106.5625,"name":"重庆"}
                    ]}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            respond(exchange, body);
        });

        server.createContext("/forecast", exchange -> {
            // 故意使用 pretty-printed 风格：key 与冒号之间、数组元素两侧都带空格，
            // 模拟真实场景下上游/代理重写后产生的空白。旧版 indexOf 切片实现会在这里崩。
            // 默认返回从今天起的 5 天，逐日变更温度/天气码以验证按日展开。
            StringBuilder body = new StringBuilder();
            body.append("{\n");
            body.append("  \"latitude\" : 29.56063,\n");
            body.append("  \"longitude\" : 106.5625,\n");
            body.append("  \"daily\" : {\n");
            LocalDate today = LocalDate.now();
            String timesField = IntStream.range(0, 5)
                    .mapToObj(today::plusDays)
                    .map(LocalDate::toString)
                    .collect(Collectors.joining("\", \"", "    \"time\" : [ \"", "\" ]"));
            String weathercodeField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 95; case 1 -> 61; case 2 -> 1; case 3 -> 0; default -> 3; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"weathercode\" : [ ", " ]"));
            String tempMaxField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 32.1; case 1 -> 30.0; case 2 -> 28.5; case 3 -> 31.2; default -> 29.8; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"temperature_2m_max\" : [ ", " ]"));
            String tempMinField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 25.3; case 1 -> 24.0; case 2 -> 22.7; case 3 -> 23.5; default -> 24.2; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"temperature_2m_min\" : [ ", " ]"));
            String windSpeedField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 8.7; case 1 -> 7.2; case 2 -> 5.5; case 3 -> 6.8; default -> 9.1; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"windspeed_10m_max\" : [ ", " ]"));
            String windDirField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 5; case 1 -> 90; case 2 -> 180; case 3 -> 270; default -> 360; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"winddirection_10m_dominant\" : [ ", " ]"));
            String humidityMaxField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 92; case 1 -> 85; case 2 -> 70; case 3 -> 65; default -> 78; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"relative_humidity_2m_max\" : [ ", " ]"));
            String humidityMinField = IntStream.range(0, 5)
                    .mapToObj(i -> switch (i) { case 0 -> 59; case 1 -> 55; case 2 -> 45; case 3 -> 40; default -> 50; })
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "    \"relative_humidity_2m_min\" : [ ", " ]"));
            body.append(timesField).append(",\n");
            body.append(weathercodeField).append(",\n");
            body.append(tempMaxField).append(",\n");
            body.append(tempMinField).append(",\n");
            body.append(windSpeedField).append(",\n");
            body.append(windDirField).append(",\n");
            body.append(humidityMaxField).append(",\n");
            body.append(humidityMinField).append("\n");
            body.append("  }\n");
            body.append("}\n");
            respond(exchange, body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });

        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        tool = new WeatherTool(
                HttpClient.newHttpClient(),
                new tools.jackson.databind.ObjectMapper(),
                base + "/geocode",
                base + "/forecast");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

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
    void rejectsInvalidStartDateFormat() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆", "start_date", "08-19")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsInvalidEndDateFormat() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", "2099-01-01",
                        "end_date", "08-19")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsEndBeforeStart() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", LocalDate.now().plusDays(3).toString(),
                        "end_date", LocalDate.now().plusDays(1).toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsPastStartDate() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", "2020-01-01")));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsDateBeyondForecastRange() {
        String farFuture = LocalDate.now().plusDays(20).toString();
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆", "start_date", farFuture)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void rejectsRangeExceedingMaxForecastDays() {
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", LocalDate.now().toString(),
                        "end_date", LocalDate.now().plusDays(20).toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
    }

    @Test
    void declaresThreeParameters() {
        assertThat(tool.name()).isEqualTo("weather");
        assertThat(tool.parameters()).hasSize(3);
        assertThat(tool.parameters().get(0).name()).isEqualTo("city");
        assertThat(tool.parameters().get(0).required()).isTrue();
        assertThat(tool.parameters().get(1).name()).isEqualTo("start_date");
        assertThat(tool.parameters().get(1).required()).isFalse();
        assertThat(tool.parameters().get(2).name()).isEqualTo("end_date");
        assertThat(tool.parameters().get(2).required()).isFalse();
    }

    @Test
    void singleDayDefaultsToToday() {
        // 不传任何日期 → start_date=today, end_date=today，只返回一天
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of("city", "重庆")));

        assertThat(result.success()).isTrue();
        String output = result.output();
        assertThat(output)
                .contains("startDate=" + LocalDate.now())
                .contains("endDate=" + LocalDate.now())
                .contains("weatherCode=95")
                .contains("tempMax=32.1");
    }

    @Test
    void futureOnlyQueryReturnsThatDay() {
        // 只查明天：forecast_days 必须从今天起覆盖到明天，再过滤出明天的数据
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", tomorrow.toString(),
                        "end_date", tomorrow.toString())));

        assertThat(result.success()).isTrue();
        String output = result.output();
        // mock 数据中明天对应 index=1：weathercode=61, tempMax=30.0
        assertThat(output)
                .contains("date=" + tomorrow)
                .contains("weatherCode=61")
                .contains("tempMax=30.0");
    }

    @Test
    void parsesFiveDayRange() {
        // 一次查询未来 5 天
        ToolResult result = tool.execute(ToolContexts.testContext(tool),
                new ToolCall("weather", Map.of(
                        "city", "重庆",
                        "start_date", LocalDate.now().toString(),
                        "end_date", LocalDate.now().plusDays(4).toString())));

        assertThat(result.success()).isTrue();
        String output = result.output();
        // 验证逐日数据都被展开
        assertThat(output)
                .contains("weatherCode=95")
                .contains("weatherCode=61")
                .contains("weatherCode=1")
                .contains("weatherCode=0")
                .contains("weatherCode=3")
                .contains("tempMax=32.1")
                .contains("tempMax=30.0")
                .contains("tempMax=28.5")
                .contains("tempMax=31.2")
                .contains("tempMax=29.8");
    }
}