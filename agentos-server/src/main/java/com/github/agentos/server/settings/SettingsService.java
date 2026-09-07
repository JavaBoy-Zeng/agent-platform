package com.github.agentos.server.settings;

import java.util.Optional;

/**
 * 运行时可调配置（key/value）。
 *
 * <p>设置由管理员通过专用 API 写入；其他模块只读。
 * 缺失项视为"使用默认行为"，不抛异常。</p>
 */
public interface SettingsService {

    /** 当前已知配置项的最大上限，用于校验与展示。 */
    String NON_ADMIN_CALL_LIMIT = "agentos.settings.non-admin-call-limit";

    /** 读取配置项；不存在时返回 {@link Optional#empty()}。 */
    Optional<String> read(String key);

    /**
     * 写入或更新配置项。{@code value} 由调用方负责序列化（通常为 JSON 文本）。
     *
     * @param key        配置主键
     * @param value      配置文本
     * @param updatedBy  写入者（管理员用户名），便于审计
     */
    void write(String key, String value, String updatedBy);

    /** 进程内实现：单例服务重启即丢失，仅用于测试与无数据库演示。 */
    final class InMemorySettingsService implements SettingsService {
        private final java.util.Map<String, java.util.Map.Entry<String, String>> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<String> read(String key) {
            java.util.Map.Entry<String, String> entry = store.get(key);
            return entry == null ? Optional.empty() : Optional.of(entry.getKey());
        }

        @Override
        public void write(String key, String value, String updatedBy) {
            store.put(key, java.util.Map.entry(value, updatedBy == null ? "" : updatedBy));
        }
    }
}