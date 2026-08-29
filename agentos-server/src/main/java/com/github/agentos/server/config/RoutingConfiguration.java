package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.loop.MainAgent;
import com.github.agentos.agent.loop.SimpleQaAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.registry.InMemoryAgentRegistry;
import com.github.agentos.agent.routing.HeuristicIntentClassifier;
import com.github.agentos.agent.routing.IntentClassifier;
import com.github.agentos.agent.routing.RoutingAgentLoop;
import com.github.agentos.agent.specialist.SupervisorAgent;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.server.model.OpenAiCompatibleChatClient;
import com.github.agentos.server.model.ModelProviderService;
import com.github.agentos.server.model.RoutingModelClients;
import com.github.agentos.server.catalog.SystemCatalogAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;


/**
 * 意图识别与 Agent 路由层装配。
 *
 * <p>该配置把 {@link RoutingAgentLoop} 接入到 {@code AgentRunner} 之前，
 * 让请求先经过 {@link IntentClassifier} 决策再进入执行循环。
 * 意图分级顺序：寒暄短路（零调用）→ 简单问答（{@link SimpleQaAgent} 单次直答）
 * → 复杂任务（{@link SupervisorAgent} LLM 分类 + 专业 Agent 派发，
 * 或回退到 {@link MainAgent} 全量规划）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RoutingConfiguration {

    /** 创建基于启发式规则的意图分类器，零 LLM 调用。 */
    @Bean
    @ConditionalOnMissingBean(IntentClassifier.class)
    IntentClassifier intentClassifier(
            @Value("${agentos.router.short-circuit.max-chars:16}") int maxChars,
            @Value("${agentos.router.short-circuit.greeting-message:你好！有什么我可以帮你的吗？}") String greetingMessage,
            @Value("${agentos.router.short-circuit.acknowledgement-message:好的。}") String acknowledgementMessage,
            @Value("${agentos.router.short-circuit.thanks-message:不客气！有需要随时告诉我。}") String thanksMessage,
            @Value("${agentos.router.short-circuit.farewell-message:晚安，祝你好梦。}") String farewellMessage,
            @Value("${agentos.router.simple-qa.max-chars:32}") int simpleQaMaxChars) {
        return new HeuristicIntentClassifier(
                maxChars, simpleQaMaxChars, SimpleQaAgent.ID, SystemCatalogAgent.ID,
                greetingMessage, acknowledgementMessage, thanksMessage, farewellMessage);
    }

    /**
     * 创建面向简单问答路径的轻量直答客户端。
     *
     * <p>复用主模型端点；{@code agentos.model.chat-model} 可指定更小更快的模型。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    ChatClient chatClient(
            ModelClientProperties properties,
            ModelProviderService providers,
            ObjectMapper objectMapper,
            com.github.agentos.planner.ModelUsageListener usageListener) {
        return new RoutingModelClients.Chat(
                providers, objectMapper, usageListener, properties);
    }

    /**
     * 创建简单问答 Agent；作为 {@link Agent} bean 会被自动注册进 AgentRegistry，
     * 供路由决策以 {@link SimpleQaAgent#ID} 派发。
     */
    @Bean
    SimpleQaAgent simpleQaAgent(ChatClient chatClient) {
        return new SimpleQaAgent(chatClient);
    }

    /**
     * 创建进程内 AgentRegistry，把 Spring 容器内全部 Agent 注入到注册表。
     *
     * <p>主 Agent、简单问答 Agent 和专业 Agent 均被注册。</p>
     */
    @Bean
    @ConditionalOnMissingBean(AgentRegistry.class)
    AgentRegistry agentRegistry(java.util.List<Agent> agents) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        agents.forEach(registry::register);
        return registry;
    }

    /**
     * 创建意图路由器，将短路、Agent派发和 fallback 三类决策统一封装为 AgentLoop。
     *
     * <p>fallback 绑定到 {@link SupervisorAgent}，后者通过单次 LLM 调用将任务
     * 分类到专业 Agent 或回退到 {@link MainAgent} 走完整规划循环。
     * 显式绑定避免与 {@link RoutingAgentLoop} 自身同为 {@code AgentLoop}
     * 实现而引发 Spring 注入歧义。</p>
     */
    @Bean
    @ConditionalOnMissingBean(RoutingAgentLoop.class)
    RoutingAgentLoop routingAgentLoop(
            IntentClassifier classifier,
            AgentRegistry registry,
            SupervisorAgent supervisorAgent) {
        return new RoutingAgentLoop(classifier, registry, supervisorAgent);
    }
}
