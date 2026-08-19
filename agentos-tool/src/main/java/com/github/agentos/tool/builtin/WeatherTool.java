package com.github.agentos.tool.builtin;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolFailureType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 创建天气工具。 */
    public WeatherTool() {
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "查询指定城市指定日期的天气信息（含气温、风、湿度），默认查今天，最多支持未来 16 天预报";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter("city", ToolParameter.ValueType.STRING,
                        "城市名称，例如：重庆、北京、上海", true),
                new ToolParameter("date", ToolParameter.ValueType.STRING,
                        "查询日期，格式 YYYY-MM-DD，默认今天；可查未来 16 天内的预报", false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        Object cityObj = call.arguments().get("city");
        if (cityObj == null || cityObj.toString().isBlank()) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "missing required argument: city");
        }
        String city = cityObj.toString();

        LocalDate date;
        Object dateObj = call.arguments().get("date");
        if (dateObj == null || dateObj.toString().isBlank()) {
            date = LocalDate.now();
        } else {
            try {
                date = LocalDate.parse(dateObj.toString().trim());
            } catch (DateTimeParseException e) {
                return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                        "invalid date format, expected YYYY-MM-DD: " + dateObj);
            }
        }

        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "cannot query past dates, today is " + today);
        }
        if (date.isAfter(today.plusDays(MAX_FORECAST_DAYS - 1))) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT,
                    "date out of forecast range (max " + MAX_FORECAST_DAYS + " days): " + date);
        }

        try {
            double[] coords = geocode(city);
            if (coords == null) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "city not found: " + city);
            }
            String result = queryForecast(city, coords[0], coords[1], date, today);
            return ToolResult.success(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(ToolFailureType.TRANSIENT, "weather query interrupted");
        } catch (Exception e) {
            return ToolResult.failure(ToolFailureType.TRANSIENT, "weather query failed: " + e.getMessage());
        }
    }

    /** 城市名 → 经纬度。 */
    private double[] geocode(String city) throws Exception {
        String url = GEOCODING_API + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=zh";
        String body = httpGet(url);

        if (body.contains("\"results\":null") || body.contains("\"results\":[]")) {
            return null;
        }
        double lat = extractDouble(body, "\"latitude\":");
        double lon = extractDouble(body, "\"longitude\":");
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null;
        }
        return new double[]{lat, lon};
    }

    /** 查询预报并提取目标日期数据。 */
    private String queryForecast(String city, double lat, double lon,
                                 LocalDate target, LocalDate today) throws Exception {
        int forecastDays = (int) (today.until(target).getDays() + 1);
        if (forecastDays < 1) forecastDays = 1;

        String url = FORECAST_API + "?latitude=" + lat + "&longitude=" + lon
                + "&daily=weathercode,temperature_2m_max,temperature_2m_min,"
                + "windspeed_10m_max,winddirection_10m_dominant,"
                + "relative_humidity_2m_max,relative_humidity_2m_min"
                + "&timezone=auto&forecast_days=" + Math.min(forecastDays, MAX_FORECAST_DAYS);

        String body = httpGet(url);

        // 定位 daily.time 数组，找目标日期索引
        int timeIdx = body.indexOf("\"time\":");
        if (timeIdx < 0) {
            return "查询到 " + city + " 的天气数据，但无法解析日期数组";
        }
        int timeStart = body.indexOf('[', timeIdx);
        int timeEnd = body.indexOf(']', timeStart);
        String timeArrayStr = body.substring(timeStart + 1, timeEnd);
        String[] dates = timeArrayStr.split("\",\"");
        int targetIdx = -1;
        for (int i = 0; i < dates.length; i++) {
            String d = dates[i].replace("\"", "").trim();
            if (d.equals(target.toString())) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            return "无法找到 " + target + " 的天气数据，可查日期范围：" + dates[0].replace("\"", "")
                    + " ~ " + dates[dates.length - 1].replace("\"", "");
        }

        // 提取各字段目标索引值
        int weatherCode = (int) extractArrayValue(body, "\"weathercode\":", targetIdx);
        double tempMax = extractArrayValue(body, "\"temperature_2m_max\":", targetIdx);
        double tempMin = extractArrayValue(body, "\"temperature_2m_min\":", targetIdx);
        double windSpeed = extractArrayValue(body, "\"windspeed_10m_max\":", targetIdx);
        double windDir = extractArrayValue(body, "\"winddirection_10m_dominant\":", targetIdx);
        int humidityMax = (int) extractArrayValue(body, "\"relative_humidity_2m_max\":", targetIdx);
        int humidityMin = (int) extractArrayValue(body, "\"relative_humidity_2m_min\":", targetIdx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("date", target.toString());
        result.put("weather", wmoDescription(weatherCode));
        result.put("weatherCode", weatherCode);
        result.put("tempMax", tempMax);
        result.put("tempMin", tempMin);
        result.put("windSpeedKmh", windSpeed);
        result.put("windDirection", windDirectionText(windDir));
        result.put("humidityMax", humidityMax);
        result.put("humidityMin", humidityMin);
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

    /** 从 JSON 字符串中提取第一个 key 后的数值。 */
    private static double extractDouble(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return Double.NaN;
        int start = idx + key.length();
        // 跳过空格
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.'
                || json.charAt(end) == '-')) end++;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 从 JSON 数组字段中提取指定索引的数值。 */
    private static double extractArrayValue(String json, String key, int index) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return Double.NaN;
        int arrStart = json.indexOf('[', keyIdx);
        int arrEnd = json.indexOf(']', arrStart);
        String arrStr = json.substring(arrStart + 1, arrEnd);
        String[] parts = arrStr.split(",");
        if (index >= parts.length) return Double.NaN;
        try {
            return Double.parseDouble(parts[index].trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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
