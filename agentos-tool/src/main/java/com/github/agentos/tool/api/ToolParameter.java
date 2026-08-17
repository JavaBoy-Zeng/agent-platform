package com.github.agentos.tool.api;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;

/**
 * Agent 工具的一个参数定义。
 *
 * <p>规划器会把该定义提供给大语言模型，计划校验器也会使用相同定义检查模型生成的
 * 工具调用，避免提示词描述和运行时校验规则不一致。</p>
 *
 * @param name 参数名称
 * @param type 参数值类型
 * @param description 参数用途说明
 * @param required 是否为必填参数
 */
public record ToolParameter(String name, ValueType type, String description, boolean required) {

    /**
     * 创建并校验工具参数定义。
     *
     * @throws IllegalArgumentException 当参数名称或说明为空时抛出
     * @throws NullPointerException 当参数类型为 {@code null} 时抛出
     */
    public ToolParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        if (type == null) {
            throw new NullPointerException("parameter type must not be null");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("parameter description must not be blank");
        }
    }

    /**
     * 判断给定运行时值是否符合参数类型。
     *
     * @param value 待检查的参数值
     * @return 类型匹配时返回 {@code true}
     */
    public boolean accepts(Object value) {
        return value != null && type.accepts(value);
    }

    /**
     * 工具参数支持的基础 JSON 数据类型。
     */
    public enum ValueType {
        /** 字符串值。 */
        STRING,
        /** 任意数值。 */
        NUMBER,
        /** 不包含小数部分的整数值。 */
        INTEGER,
        /** 布尔值。 */
        BOOLEAN,
        /** 键值对象。 */
        OBJECT,
        /** 数组或集合。 */
        ARRAY;

        /**
         * 判断运行时值是否属于当前类型。
         *
         * @param value 待检查的非空值
         * @return 类型匹配时返回 {@code true}
         */
        boolean accepts(Object value) {
            return switch (this) {
                case STRING -> value instanceof String;
                case NUMBER -> value instanceof Number;
                case INTEGER -> value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof BigInteger;
                case BOOLEAN -> value instanceof Boolean;
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof Collection<?> || value.getClass().isArray();
            };
        }
    }
}
