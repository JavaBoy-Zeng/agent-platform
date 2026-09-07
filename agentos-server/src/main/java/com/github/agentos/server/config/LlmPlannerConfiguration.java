package com.github.agentos.server.config;

import com.github.agentos.memory.MemoryService;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.LlmAgentPlanner;
import com.github.agentos.planner.ModelClient;
import com.github.agentos.planner.PlanValidator;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.model.NonAdminCallLimiter;
import com.github.agentos.server.model.RoutingModelClients;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;


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
     * 创建默认的、支持 Provider 路由的模型客户端。
     *
     * <p>业务应用可以自行声明 {@link ModelClient} Bean 覆盖该默认适配器：
     * {@code @ConditionalOnMissingBean} 保证仅在容器中没有任何 {@link ModelClient}
     * 实现时才装配本兜底实现。</p>
     *
     * <p>每次规划调用都会解析 {@code planner} 路由，并由
     * {@link RoutingModelClients.Planner} 校验最终的端点、模型名等关键配置。</p>
     *
     * @param properties      模型端点配置（绑定 {@code agentos.model.*}）
     * @param objectMapper    应用 JSON 映射器
     * @param usageListener   模型调用成功后的用量回调，用于 token 记账
     * @param fileAccessPolicy 文件访问策略，用于约束模型规划出的文件路径
     * @return 模型客户端适配器
     */
    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    ModelClient openAiCompatibleModelClient(
            ModelClientProperties properties,
            ModelProviderService providers,
            ObjectMapper objectMapper,
            com.github.agentos.planner.ModelUsageListener usageListener,
            FileAccessPolicy fileAccessPolicy,
            com.github.agentos.kernel.SessionService sessionService,
            com.github.agentos.server.security.UserStore userStore,
            NonAdminCallLimiter nonAdminCallLimiter) {
        // Provider 与模型由 planner 路由在每次调用时解析；连接超时、请求超时等网络参数
        // 继续继承 agentos.model.* 的默认配置。
        // allowedRoot() 限定模型生成文件读写路径的根目录；没有单一根目录时传 null。
        return new RoutingModelClients.Planner(
                providers, objectMapper, usageListener, properties,
                fileAccessPolicy.allowedRoot().orElse(null),
                sessionService, userStore, nonAdminCallLimiter);
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
