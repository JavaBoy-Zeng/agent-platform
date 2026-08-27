package com.github.agentos.server.config;

import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.server.catalog.RuntimeCatalogService;
import com.github.agentos.server.catalog.SystemCatalogAgent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配本地运行时目录 Agent；使用延迟 Provider 避免工具与 Agent 注册表循环依赖。 */
@Configuration(proxyBeanMethods = false)
public class RuntimeCatalogConfiguration {

    @Bean
    SystemCatalogAgent systemCatalogAgent(
            ObjectProvider<RuntimeCatalogService> catalogService) {
        return new SystemCatalogAgent(() -> catalogService.getObject().snapshot());
    }

    @Bean
    AgentToolAdapter systemCatalogAgentTool(SystemCatalogAgent systemCatalogAgent) {
        return new AgentToolAdapter(systemCatalogAgent);
    }
}
