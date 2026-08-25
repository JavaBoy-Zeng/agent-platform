package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.config.AgentBuildContext;
import com.github.agentos.agent.config.AgentDefinition;
import com.github.agentos.agent.config.AgentFactory;
import com.github.agentos.agent.config.ConfigAgentLoader;
import com.github.agentos.agent.config.ConfigDrivenAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * YAML 配置驱动 Agent 的 Spring 装配。
 *
 * <p>从 {@code agentos.agents.config-file} 指定的 YAML 文件加载 Agent 定义，
 * 用 {@link AgentFactory} 构建实例，注册到 {@link AgentRegistry} 和（可选）
 * {@link ToolRegistry}（通过 {@link AgentToolAdapter}）。</p>
 *
 * <p>未配置文件或文件不存在时跳过，不影响已有 Bean。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.agents.config-file")
public class ConfigAgentConfiguration implements SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigAgentConfiguration.class);

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final String configFile;
    private final FileAccessPolicy fileAccessPolicy;

    public ConfigAgentConfiguration(
            ChatClient chatClient,
            ToolRegistry toolRegistry,
            AgentRegistry agentRegistry,
            ObjectMapper objectMapper,
            @Qualifier("rootedFileAccessPolicy") FileAccessPolicy fileAccessPolicy,
            @Value("${agentos.agents.config-file:}") String configFile) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
        this.fileAccessPolicy = fileAccessPolicy;
        this.configFile = configFile;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (configFile == null || configFile.isBlank()) {
            return;
        }
        Path path = fileAccessPolicy.authorizeRead(Path.of(configFile));
        if (!Files.exists(path)) {
            LOGGER.info("[config-agents] config file not found, skipping: {}", path);
            return;
        }
        try {
            ConfigAgentLoader loader = new ConfigAgentLoader(objectMapper);
            List<AgentDefinition> definitions = loader.load(path);
            if (definitions.isEmpty()) {
                LOGGER.info("[config-agents] no agents defined in {}", path);
                return;
            }
            AgentBuildContext context = new AgentBuildContext(chatClient, toolRegistry);
            AgentFactory factory = new AgentFactory(context);
            List<Agent> agents = factory.buildAll(definitions);

            for (Agent agent : agents) {
                agentRegistry.register(agent);
                LOGGER.info("[config-agents] registered agent id={}", agent.id());

                // 暴露为工具
                if (agent instanceof ConfigDrivenAgent driven && driven.definition().exposeAsTool()) {
                    AgentToolAdapter adapter = new AgentToolAdapter(driven);
                    try {
                        toolRegistry.register(adapter);
                        LOGGER.info("[config-agents] exposed agent as tool name={}", agent.id());
                    } catch (IllegalArgumentException alreadyRegistered) {
                        LOGGER.info("[config-agents] tool already registered: {}", agent.id());
                    }
                }
            }
            LOGGER.info("[config-agents] loaded {} agents from {}", agents.size(), path);
        } catch (Exception e) {
            LOGGER.warn("[config-agents] failed to load from {}: {}", path, e.getMessage(), e);
        }
    }
}
