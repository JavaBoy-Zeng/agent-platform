package com.github.agentos.server.catalog;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.config.ConfigDrivenAgent;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.server.config.mcp.McpProperties;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.tool.api.ToolDefinition;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.skill.AgentSkill;
import com.github.agentos.tool.skill.SkillRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 统一提供 Console 与系统自省 Agent 使用的只读运行时目录。 */
@Service
public final class RuntimeCatalogService {
    private final ToolRegistry toolRegistry;
    private final ObjectProvider<AgentRegistry> agentRegistry;
    private final ObjectProvider<SkillRegistry> skillRegistry;
    private final ObjectProvider<McpProperties> mcpProperties;
    private final ModelClientProperties modelProperties;
    private final AgentExecutionLimits limits;

    public RuntimeCatalogService(
            ToolRegistry toolRegistry,
            ObjectProvider<AgentRegistry> agentRegistry,
            ObjectProvider<SkillRegistry> skillRegistry,
            ObjectProvider<McpProperties> mcpProperties,
            ModelClientProperties modelProperties,
            AgentExecutionLimits limits) {
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.mcpProperties = mcpProperties;
        this.modelProperties = modelProperties;
        this.limits = limits;
    }

    public CatalogSnapshot snapshot() {
        List<ToolView> tools = toolRegistry.definitions().stream().map(ToolView::from).toList();
        SkillRegistry skills = skillRegistry.getIfAvailable();
        List<SkillView> skillViews = skills == null ? List.of()
                : skills.all().stream().map(SkillView::from).toList();
        McpProperties mcp = mcpProperties.getIfAvailable();
        List<McpServerView> servers = mcp == null ? List.of()
                : mcp.getServers().stream().map(server -> new McpServerView(
                        server.name(), mcp.isEnabled() ? "CONFIGURED" : "DISABLED",
                        server.command() == null || server.command().isEmpty()
                                ? "" : server.command().getFirst())).toList();
        return new CatalogSnapshot(
                agents(), tools, skillViews, servers,
                List.of(
                        new ModelView("planner", modelProperties.getModel(),
                                provider(modelProperties.getEndpoint()), "PLANNING"),
                        new ModelView("direct-chat", modelProperties.getEffectiveChatModel(),
                                provider(modelProperties.getEndpoint()), "CHAT")),
                Map.of(
                        "maxReplans", limits.maxReplanCount(),
                        "maxSteps", limits.maxStepCount(),
                        "maxToolCalls", limits.maxToolCalls(),
                        "maxModelCalls", limits.maxModelCalls()));
    }

    public Optional<AgentDetail> agent(String agentId) {
        AgentRegistry registry = agentRegistry.getIfAvailable();
        if (registry == null) return Optional.empty();
        Set<String> exposed = exposedToolNames();
        return registry.find(agentId).map(agent -> AgentDetail.from(agent, exposed));
    }

    private List<AgentView> agents() {
        AgentRegistry registry = agentRegistry.getIfAvailable();
        if (registry == null || registry.all().isEmpty()) {
            return List.of(new AgentView("main-agent", "Main Agent", "READY",
                    "规划、工具调用、记忆与审批编排入口", "planner", false, 0));
        }
        Set<String> exposed = exposedToolNames();
        return registry.all().stream().map(agent -> AgentView.from(agent, exposed)).toList();
    }

    private Set<String> exposedToolNames() {
        return toolRegistry.all().stream()
                .map(com.github.agentos.tool.api.AgentTool::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String provider(URI endpoint) {
        return endpoint == null || endpoint.getHost() == null
                ? "OpenAI compatible" : endpoint.getHost();
    }

    public record CatalogSnapshot(
            List<AgentView> agents, List<ToolView> tools, List<SkillView> skills,
            List<McpServerView> mcpServers, List<ModelView> models,
            Map<String, Integer> limits) {
    }

    public record AgentView(
            String id, String name, String status, String description,
            String kind, boolean exposedAsTool, int subAgentCount) {
        static AgentView from(Agent agent, Set<String> exposedToolNames) {
            return new AgentView(agent.id(), displayName(agent.id()), "READY", agent.description(),
                    kindOf(agent), exposedToolNames.contains(agent.id()), countSubAgents(agent));
        }
    }

    public record AgentDetail(
            String id, String name, String status, String description,
            String kind, boolean exposedAsTool, List<String> subAgents, List<String> tools) {
        static AgentDetail from(Agent agent, Set<String> exposedToolNames) {
            List<String> subAgents = agent instanceof BaseAgent base
                    ? base.subAgents().stream().map(BaseAgent::id).toList() : List.of();
            List<String> tools = agent instanceof ConfigDrivenAgent driven
                    ? driven.definition().tools() : List.of();
            return new AgentDetail(agent.id(), displayName(agent.id()), "READY", agent.description(),
                    kindOf(agent), exposedToolNames.contains(agent.id()), subAgents, tools);
        }
    }

    public record ToolView(
            String name, String description, String riskLevel, int parameterCount,
            List<String> parameters) {
        static ToolView from(ToolDefinition definition) {
            return new ToolView(definition.name(), definition.description(),
                    definition.riskLevel().name(), definition.parameters().size(),
                    definition.parameters().stream().map(parameter -> parameter.name()).toList());
        }
    }

    public record SkillView(String id, String name, String description, String source) {
        static SkillView from(AgentSkill skill) {
            return new SkillView(skill.id(), skill.name(), skill.description(), skill.source());
        }
    }

    public record McpServerView(String name, String status, String transport) {
    }

    public record ModelView(String role, String model, String provider, String workload) {
    }

    private static String displayName(String id) {
        String[] parts = id.split("[-_]");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.isEmpty() ? id : name.toString();
    }

    private static String kindOf(Agent agent) {
        if (agent instanceof ConfigDrivenAgent driven) return driven.definition().kind();
        if (agent instanceof BaseAgent base && !base.subAgents().isEmpty()) return "workflow";
        return switch (agent.id()) {
            case "main-agent" -> "planner";
            case "supervisor-agent" -> "supervisor";
            case "simple-qa-agent" -> "direct";
            default -> "specialist";
        };
    }

    private static int countSubAgents(Agent agent) {
        return agent instanceof BaseAgent base ? base.subAgents().size() : 0;
    }
}
