package com.github.agentos.kernel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 会话的结构化状态。
 *
 * <p>区别于跨会话的 {@link com.github.agentos.memory.MemoryService}：
 * SessionState 描述<b>当前会话内</b>的执行状态（例如用户偏好的单位、任务标识、
 * 轮次计数），随会话存续、随事件增量演进。</p>
 */
public final class SessionState {

    private static final SessionState EMPTY = new SessionState(Map.of());

    private final Map<String, Object> values;

    private SessionState(Map<String, Object> values) {
        this.values = values;
    }

    /** 创建空状态。 */
    public static SessionState empty() {
        return EMPTY;
    }

    /** 从键值快照创建状态；入参会被复制为不可变视图。 */
    public static SessionState of(Map<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        return values.isEmpty() ? EMPTY : new SessionState(Map.copyOf(values));
    }

    /** 返回设置单个键值后的新状态。 */
    public SessionState withValue(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put(key, value);
        return new SessionState(Map.copyOf(next));
    }

    /** 返回合并增量后的新状态；后写入的键覆盖既有值。 */
    public SessionState withDelta(Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.putAll(delta);
        return new SessionState(Map.copyOf(next));
    }

    /** 读取键对应的值。 */
    public Object value(String key) {
        return values.get(key);
    }

    /** 以字符串读取键值，缺失时返回默认值。 */
    public String stringValue(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 以 long 读取数值键值（兼容 Number 与数字字符串），缺失或非法时返回默认值。 */
    public long longValue(String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 键值对数量。 */
    public int size() {
        return values.size();
    }

    /** 返回不可变键值快照。 */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionState that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "SessionState" + values;
    }
}
