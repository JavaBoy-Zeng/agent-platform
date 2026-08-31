package com.github.agentos.server.config;

import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.persistence.mybatis.ModelProviderMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/** 多 Provider 模型配置服务装配。 */
@Configuration(proxyBeanMethods = false)
public class ModelProviderConfiguration {

    @Bean
    ModelProviderService modelProviderService(
            @Value("${agentos.persistence.mode:postgresql}") String persistenceMode,
            @Value("${agentos.model.secret-key:}") String secretKey,
            ObjectProvider<ModelProviderMapper> providerMapper,
            ObjectMapper objectMapper) {
        boolean postgresql = persistenceMode != null
                && persistenceMode.trim().toLowerCase(Locale.ROOT).matches("postgres(ql)?");
        return new ModelProviderService(
                persistenceMode,
                postgresql ? providerMapper.getObject() : null,
                objectMapper, secretKey);
    }
}
