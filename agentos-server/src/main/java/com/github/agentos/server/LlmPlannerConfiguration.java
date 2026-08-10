package com.github.agentos.server;

import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.LlmTaskPlanner;
import com.github.agentos.planner.ModelClient;
import com.github.agentos.planner.PlanValidator;
import com.github.agentos.planner.TaskPlanner;
import com.github.agentos.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 非演示环境的大语言模型规划器装配。
 *
 * <p>该配置要求应用提供一个具体的 {@link ModelClient} Bean。模型厂商选择、鉴权和网络调用
 * 不属于规划核心模块，由服务端适配器或业务应用实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!demo")
public class LlmPlannerConfiguration {

    /**
     * 创建大语言模型规划器配置。
     */
    public LlmPlannerConfiguration() {
    }

    /**
     * 创建生产配置使用的大语言模型任务规划器。
     *
     * @param modelClient 模型客户端适配器
     * @param toolRegistry 工具注册表
     * @param memoryService 记忆服务
     * @param planValidator 计划校验器
     * @return 大语言模型任务规划器
     */
    @Bean
    TaskPlanner llmTaskPlanner(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator) {
        return new LlmTaskPlanner(modelClient, toolRegistry, memoryService, planValidator);
    }
}
