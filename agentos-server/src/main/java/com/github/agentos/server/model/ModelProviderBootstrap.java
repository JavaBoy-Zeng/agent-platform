package com.github.agentos.server.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 首次启动引导：model_providers 为空时按 agentos.model.* 兜底配置自动 seed。
 *
 * <p>避免普通账号在没有 ADMIN 角色、无法访问 /api/model-management 时陷入
 * 「没有任何可用模型 → 应用不可用」的死锁；已有 Provider 时直接跳过，
 * 保留运维手工配置。</p>
 */
@Component
@EnableConfigurationProperties(ModelClientProperties.class)
public final class ModelProviderBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelProviderBootstrap.class);

    private final ModelProviderService providers;
    private final ModelClientProperties defaults;

    public ModelProviderBootstrap(ModelProviderService providers, ModelClientProperties defaults) {
        this.providers = providers;
        this.defaults = defaults;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            defaults.validate();
        } catch (RuntimeException exception) {
            // 兜底配置不合法时不要阻塞启动——admin 可在 UI 内手工补齐。
            LOGGER.warn("[model-bootstrap] skip seed: invalid agentos.model.* ({})",
                    exception.getMessage());
            return;
        }
        try {
            providers.seedFromProperties(defaults).ifPresent(provider ->
                    LOGGER.info("[model-bootstrap] seeded default model provider id={} "
                                    + "displayName={} model={} endpoint={}",
                            provider.id(), provider.displayName(),
                            provider.models().isEmpty() ? " ? " : provider.models().get(0),
                            provider.endpoint()));
        } catch (RuntimeException exception) {
            // 自动 seed 失败不能阻塞应用启动：admin 可在 UI 内手动添加 provider。
            LOGGER.warn("[model-bootstrap] seed failed: {} (use /api/model-management as admin)",
                    exception.getMessage());
        }
    }
}