package com.github.agentos.tool.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具参数结构与 JSON Schema 之间的转换工具。
 *
 * <p>JSON Schema 是工具参数的统一交换格式：OpenAI、Gemini、Claude、MCP 与
 * OpenAPI 的函数调用描述都能从同一份 schema 生成或转换。</p>
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    /**
     * 把工具参数定义转换为标准 object JSON Schema。
     *
     * @param parameters 工具参数定义列表
     * @return 形如 {@code {"type":"object","properties":{...},"required":[...]}}
     *         的只读 schema 映射
     */
    public static Map<String, Object> fromParameters(List<ToolParameter> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonType(parameter.type()));
            property.put("description", parameter.description());
            if (parameter.type() == ToolParameter.ValueType.ARRAY) {
                // 当前内置数组参数（路径、过滤规则）均为字符串列表。
                property.put("items", Map.of("type", "string"));
            }
            properties.put(parameter.name(), Map.copyOf(property));
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static String jsonType(ToolParameter.ValueType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMBER -> "number";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case OBJECT -> "object";
            case ARRAY -> "array";
        };
    }
}
