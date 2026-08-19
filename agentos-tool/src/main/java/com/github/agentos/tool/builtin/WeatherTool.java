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
import java.util.List;

/**
 * 天气查询工具。
 *
 * <p>
 * 用于 AgentOS 调用外部天气 API，
 * 查询指定城市当前天气信息。
 * </p>
 */
public final class WeatherTool implements AgentTool {


    /**
     * 天气接口地址。
     */
    private static final String WEATHER_API = "https://uapis.cn/api/v1/misc/weather";


    /**
     * HTTP客户端。
     */
    private final HttpClient httpClient;


    /**
     * 创建天气工具。
     */
    public WeatherTool() {
        this.httpClient = HttpClient.newHttpClient();
    }


    /**
     * 获取工具名称。
     *
     * @return weather
     */
    @Override
    public String name() {
        return "weather";
    }


    /**
     * 获取工具描述。
     *
     * @return 工具说明
     */
    @Override
    public String description() {
        return "查询指定城市当前天气信息";
    }


    /**
     * 获取工具参数定义。
     *
     * @return 参数列表
     */
    @Override
    public List<ToolParameter> parameters() {

        return List.of(new ToolParameter("city", ToolParameter.ValueType.STRING, "城市名称，例如：重庆、北京、上海", true));
    }


    /**
     * 执行天气查询。
     *
     * @param call 工具调用
     * @return 天气结果
     */
    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        Object city = call.arguments().get("city");
        if (city == null) {
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "missing required argument: city");
        }
        try {
            String result = queryWeather(city.toString());
            return ToolResult.success(result);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.failure(ToolFailureType.TRANSIENT, "weather query failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure(ToolFailureType.UNKNOWN, "weather query failed: " + e.getMessage());
        }
    }


    /**
     * 请求天气接口。
     *
     * @param city 城市
     * @return API返回内容
     */
    private String queryWeather(String city) throws java.io.IOException, InterruptedException {


        String url = WEATHER_API + "?city=" + URLEncoder.encode(city, StandardCharsets.UTF_8);


        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().header("Accept", "application/json").build();


        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());


        if (response.statusCode() != 200) {

            throw new RuntimeException("http status=" + response.statusCode());
        }


        return response.body();
    }
}
