package com.github.agentos.server.config;

import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.automation.AutomationScheduleCalculator;
import com.github.agentos.server.automation.AutomationService;
import com.github.agentos.server.automation.AutomationStore;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.persistence.mybatis.AutomationClientMapper;
import com.github.agentos.server.persistence.mybatis.AutomationExecutionMapper;
import com.github.agentos.server.persistence.mybatis.AutomationTaskMapper;
import com.github.agentos.server.run.AgentRunCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** 自动化任务持久化、调度扫描和执行派发装配。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AutomationConfiguration {

    @Bean
    AutomationScheduleCalculator automationScheduleCalculator() {
        return new AutomationScheduleCalculator();
    }

    @Bean
    AutomationStore automationStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<AutomationTaskMapper> taskMapper,
            ObjectProvider<AutomationExecutionMapper> executionMapper,
            ObjectProvider<AutomationClientMapper> clientMapper,
            ObjectMapper objectMapper) {
        return new AutomationStore(mode, taskMapper.getIfAvailable(),
                executionMapper.getIfAvailable(), clientMapper.getIfAvailable(), objectMapper);
    }

    @Bean
    AutomationService automationService(
            AutomationStore store,
            AutomationScheduleCalculator calculator,
            ModelProviderService modelProviderService,
            SessionService sessionService,
            SessionHistoryService sessionHistoryService,
            AgentRunCoordinator coordinator) {
        return new AutomationService(store, calculator, modelProviderService,
                sessionService, sessionHistoryService, coordinator);
    }
}
