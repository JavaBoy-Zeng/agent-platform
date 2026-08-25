package com.github.agentos.server.config;

import com.github.agentos.memory.MemoryService;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.LlmAgentPlanner;
import com.github.agentos.planner.ModelClient;
import com.github.agentos.planner.PlanValidator;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.server.model.OpenAiCompatibleModelClient;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/**
 * 非演示环境的大语言模型规划器装配。
 *
 * <p>该配置要求应用提供一个具体的 {@link ModelClient} Bean。模型厂商选择、鉴权和网络调用
 * 不属于规划核心模块，由服务端适配器或业务应用实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelClientProperties.class)
public class LlmPlannerConfiguration {

    /**
     * 创建大语言模型规划器配置。
     */
    public LlmPlannerConfiguration() {
    }

    /**
     * 创建默认的 OpenAI-compatible 模型客户端。
     *
     * <p>业务应用可以自行声明 {@link ModelClient} Bean 覆盖该默认适配器。</p>
     *
     * @param properties 模型端点配置
     * @param objectMapper 应用 JSON 映射器
     * @return 模型客户端适配器
     */
    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    ModelClient openAiCompatibleModelClient(
            ModelClientProperties properties,
            ObjectMapper objectMapper,
            com.github.agentos.planner.ModelUsageListener usageListener,
            FileAccessPolicy fileAccessPolicy) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new OpenAiCompatibleModelClient(
                httpClient, objectMapper, properties, usageListener,
                fileAccessPolicy.allowedRoot().orElse(null));
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
    AgentPlanner llmAgentPlanner(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MemoryService memoryService,
            PlanValidator planValidator,
            AgentExecutionLimits limits,
            FileAccessPolicy fileAccessPolicy) {
        return new LlmAgentPlanner(
                modelClient, toolRegistry, memoryService, planValidator, limits,
                fileAccessPolicy.allowedRoot().orElse(null));
    }
}
