package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.loop.MainAgent;
import com.github.agentos.agent.loop.SimpleQaAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.registry.InMemoryAgentRegistry;
import com.github.agentos.agent.routing.HeuristicIntentClassifier;
import com.github.agentos.agent.routing.IntentClassifier;
import com.github.agentos.agent.routing.RoutingAgentLoop;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.server.model.OpenAiCompatibleChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/**
 * 意图识别与 Agent 路由层装配。
 *
 * <p>该配置把 {@link RoutingAgentLoop} 接入到 {@code AgentRuntime} 之前，
 * 让请求先经过 {@link IntentClassifier} 决策再进入执行循环。
 * 意图分级顺序：寒暄短路（零调用）→ 简单问答（{@link SimpleQaAgent} 单次直答）
 * → 复杂任务（{@link MainAgent} 规划执行）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RoutingConfiguration {

    /** 创建基于启发式规则的意图分类器，零 LLM 调用。 */
    @Bean
    @ConditionalOnMissingBean(IntentClassifier.class)
    IntentClassifier intentClassifier(
            @Value("${agentos.router.short-circuit.max-chars:16}") int maxChars,
            @Value("${agentos.router.short-circuit.message:收到，我已记录你的输入。}") String message,
            @Value("${agentos.router.simple-qa.max-chars:64}") int simpleQaMaxChars) {
        return new HeuristicIntentClassifier(maxChars, message, simpleQaMaxChars, SimpleQaAgent.ID);
    }

    /**
     * 创建面向简单问答路径的轻量直答客户端。
     *
     * <p>复用主模型端点；{@code agentos.model.chat-model} 可指定更小更快的模型。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    ChatClient chatClient(ModelClientProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new OpenAiCompatibleChatClient(httpClient, objectMapper, properties);
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
     * <p>主 Agent 默认被注册，标识为 {@link MainAgent#id()}。</p>
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
     * <p>fallback 直接绑定到 {@link MainAgent} bean，避免与本 {@link RoutingAgentLoop} bean
     * 自身同为 {@code AgentLoop} 实现而引发 Spring 注入歧义。</p>
     */
    @Bean
    @ConditionalOnMissingBean(RoutingAgentLoop.class)
    RoutingAgentLoop routingAgentLoop(
            IntentClassifier classifier,
            AgentRegistry registry,
            MainAgent mainAgent) {
        return new RoutingAgentLoop(classifier, registry, mainAgent);
    }
}