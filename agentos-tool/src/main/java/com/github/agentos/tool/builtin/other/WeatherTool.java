package com.github.agentos.tool.builtin.other;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolFailureType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 天气查询工具（Open-Meteo）。
 *
 * <p>数据源为 Open-Meteo 免费 API，无需 API Key。先通过 geocoding API 把城市名解析为经纬度，
 * 再调用 forecast API 获取多日预报。支持按日期查询指定天的天气。</p>
 */
public final class WeatherTool implements AgentTool {

    private static final String GEOCODING_API =
            "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_API =
            "https://api.open-meteo.com/v1/forecast";
    private static final int MAX_FORECAST_DAYS = 16;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String geocodingEndpoint;
    private final String forecastEndpoint;

    /** 创建天气工具，使用默认 {@link ObjectMapper} 与 Open-Meteo 公开端点。 */
    public WeatherTool() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), GEOCODING_API, FORECAST_API);
    }

    /** 创建天气工具，注入共享的 {@link ObjectMapper}。 */
    public WeatherTool(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, GEOCODING_API, FORECAST_API);
    }

    /** 创建天气工具，允许注入 HTTP 客户端、JSON 解析器以及自定义端点（测试用）。 */
    public WeatherTool(HttpClient httpClient, ObjectMapper objectMapper,
                       String geocodingEndpoint, String forecastEndpoint) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.geocodingEndpoint = Objects.requireNonNull(geocodingEndpoint, "geocodingEndpoint");
        this.forecastEndpoint = Objects.requireNonNull(forecastEndpoint, "forecastEndpoint");
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "查询指定城市在指定日期范围内的天气（含气温、风、湿度），每次最多返回 16 天。"
                + "多日查询请传 start_date 与 end_date，避免逐天往返。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter("city", ToolParameter.ValueType.STRING,
                        "城市名称，例如：重庆、北京、上海", true),
                new ToolParameter("start_date", ToolParameter.ValueType.STRING,
                        "起始日期，格式 YYYY-MM-DD（含），默认今天", false),
                new ToolParameter("end_date", ToolParameter.ValueType.STRING,
                        "结束日期，格式 YYYY-MM-DD（含），默认等于 start_date；不得超过今天 + 15 天", false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        Object cityObj = call.arguments().get("city");
        if (cityObj == null || cityObj.toString().isBlank()) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "missing required argument: city");
        }
        String city = cityObj.toString();

        LocalDate today = LocalDate.now();
        LocalDate startDate;
        try {
            startDate = parseDate(call.arguments().get("start_date"), today);
        } catch (DateTimeParseException e) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "invalid start_date, expected YYYY-MM-DD: " + e.getParsedString());
        }
        LocalDate endDate;
        try {
            endDate = parseDate(call.arguments().get("end_date"), startDate);
        } catch (DateTimeParseException e) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "invalid end_date, expected YYYY-MM-DD: " + e.getParsedString());
        }

        if (endDate.isBefore(startDate)) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "end_date must be on or after start_date: " + startDate + " ~ " + endDate);
        }
        if (startDate.isBefore(today)) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "cannot query past dates, today is " + today);
        }
        if (endDate.isAfter(today.plusDays(MAX_FORECAST_DAYS - 1))) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "end_date out of forecast range (max " + MAX_FORECAST_DAYS + " days): " + endDate);
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_FORECAST_DAYS) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "date range exceeds max " + MAX_FORECAST_DAYS + " days: "
                            + startDate + " ~ " + endDate);
        }

        try {
            double[] coords = geocode(city);
            if (coords == null) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "city not found: " + city);
            }
            String result = queryForecast(city, coords[0], coords[1], today, startDate, endDate);
            return ToolResult.success(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(ToolFailureType.TRANSIENT, "weather query interrupted");
        } catch (Exception e) {
            return ToolResult.failure(ToolFailureType.TRANSIENT, "weather query failed: " + e.getMessage());
        }
    }

    /** 解析日期参数；为空时回退到 fallback。 */
    private static LocalDate parseDate(Object raw, LocalDate fallback) {
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        return LocalDate.parse(raw.toString().trim());
    }

    /** 城市名 → 经纬度。 */
    private double[] geocode(String city) throws Exception {
        String url = geocodingEndpoint + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=zh";
        String body = httpGet(url);

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode results = root.path("results");
        if (results.isMissingNode() || results.isNull() || !results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode first = results.get(0);
        if (first == null || first.isMissingNode() || first.isNull()) {
            return null;
        }
        JsonNode latNode = first.path("latitude");
        JsonNode lonNode = first.path("longitude");
        if (!latNode.isNumber() || !lonNode.isNumber()) {
            return null;
        }
        return new double[]{latNode.asDouble(), lonNode.asDouble()};
    }

    /**
     * 查询预报并把 [start, end] 区间内的逐日数据展开为列表。
     * forecast_days 以今天为基准计算（Open-Meteo 语义），再用区间过滤出目标日期。
     */
    private String queryForecast(String city, double lat, double lon,
                                 LocalDate today, LocalDate start, LocalDate end) throws Exception {
        // Open-Meteo 的 forecast_days 从今天起往后数，必须覆盖到 end 而非 start
        int forecastDays = (int) java.time.temporal.ChronoUnit.DAYS.between(today, end) + 1;
        if (forecastDays < 1) forecastDays = 1;

        String url = forecastEndpoint + "?latitude=" + lat + "&longitude=" + lon
                + "&daily=weathercode,temperature_2m_max,temperature_2m_min,"
                + "windspeed_10m_max,winddirection_10m_dominant,"
                + "relative_humidity_2m_max,relative_humidity_2m_min"
                + "&timezone=auto&forecast_days=" + forecastDays;

        String body = httpGet(url);

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            return "查询到 " + city + " 的天气数据，但响应不是合法 JSON：" + e.getOriginalMessage();
        }
        JsonNode daily = root.path("daily");
        JsonNode times = daily.path("time");
        if (!times.isArray() || times.isEmpty()) {
            return "查询到 " + city + " 的天气数据，但响应缺少 daily.time 数组";
        }

        List<Map<String, Object>> days = new ArrayList<>(times.size());
        int firstIdx = -1;
        int lastIdx = -1;
        for (int i = 0; i < times.size(); i++) {
            JsonNode t = times.get(i);
            if (t == null) {
                continue;
            }
            String dateStr = t.asText();
            if (dateStr.isBlank()) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(dateStr);
                if (date.isBefore(start) || date.isAfter(end)) {
                    continue;
                }
            } catch (DateTimeParseException e) {
                continue;
            }
            int weatherCode = daily.path("weathercode").path(i).asInt();
            double tempMax = daily.path("temperature_2m_max").path(i).asDouble();
            double tempMin = daily.path("temperature_2m_min").path(i).asDouble();
            double windSpeed = daily.path("windspeed_10m_max").path(i).asDouble();
            double windDir = daily.path("winddirection_10m_dominant").path(i).asDouble();
            int humidityMax = daily.path("relative_humidity_2m_max").path(i).asInt();
            int humidityMin = daily.path("relative_humidity_2m_min").path(i).asInt();

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", dateStr);
            day.put("weather", wmoDescription(weatherCode));
            day.put("weatherCode", weatherCode);
            day.put("tempMax", tempMax);
            day.put("tempMin", tempMin);
            day.put("windSpeedKmh", windSpeed);
            day.put("windDirection", windDirectionText(windDir));
            day.put("humidityMax", humidityMax);
            day.put("humidityMin", humidityMin);
            days.add(day);
            if (firstIdx < 0) firstIdx = i;
            lastIdx = i;
        }

        if (days.isEmpty()) {
            String first = times.get(0).asText("");
            String last = times.get(times.size() - 1).asText("");
            return "无法找到 " + start + " ~ " + end + " 的天气数据，可查日期范围：" + first + " ~ " + last;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("days", days);
        return result.toString();
    }

    /** HTTP GET 返回 body。 */
    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("http status=" + response.statusCode());
        }
        return response.body();
    }

    /** WMO 天气代码 → 中文描述。 */
    private static String wmoDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "冰粒";
            case 80 -> "小阵雨";
            case 81 -> "中阵雨";
            case 82 -> "大阵雨";
            case 85 -> "小阵雪";
            case 86 -> "大阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知(" + code + ")";
        };
    }

    /** 风向角度 → 中文方位。 */
    private static String windDirectionText(double deg) {
        String[] dirs = {"北", "东北偏北", "东北", "东北偏东", "东", "东南偏东",
                "东南", "东南偏南", "南", "西南偏南", "西南", "西南偏西",
                "西", "西北偏西", "西北", "西北偏北"};
        int idx = (int) Math.round(deg / 22.5) % 16;
        return dirs[idx] + "风";
    }
}
