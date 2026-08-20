package com.github.agentos.server.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为 Console 管理面板提供不包含密钥的运行时只读目录。 */
@RestController
@RequestMapping("/api/console")
public final class ConsoleCatalogController {

    private final ToolRegistry toolRegistry;
    private final ObjectProvider<AgentRegistry> agentRegistry;
    private final ObjectProvider<SkillRegistry> skillRegistry;
    private final ObjectProvider<McpProperties> mcpProperties;
    private final ModelClientProperties modelProperties;
    private final AgentExecutionLimits limits;

    /** 创建 Console 目录控制器。 */
    public ConsoleCatalogController(
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

    /** 返回 Agent、工具、MCP、技能、模型与运行预算的当前配置快照。 */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        List<ToolView> tools = toolRegistry.definitions().stream().map(ToolView::from).toList();
        SkillRegistry registry = skillRegistry.getIfAvailable();
        List<SkillView> skills = registry == null ? List.of()
                : registry.all().stream().map(SkillView::from).toList();
        McpProperties mcp = mcpProperties.getIfAvailable();
        List<McpServerView> servers = mcp == null ? List.of()
                : mcp.getServers().stream().map(server -> new McpServerView(
                        server.name(), mcp.isEnabled() ? "CONFIGURED" : "DISABLED",
                        server.command() == null || server.command().isEmpty()
                                ? "" : server.command().getFirst())).toList();
        return new CatalogResponse(
                agents(),
                tools,
                skills,
                servers,
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

    /** 返回单个 Agent 的详情；未注册时返回 404。 */
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<AgentDetail> agent(@PathVariable String agentId) {
        AgentRegistry registry = agentRegistry.getIfAvailable();
        if (registry == null) {
            return ResponseEntity.notFound().build();
        }
        return registry.find(agentId)
                .map(agent -> ResponseEntity.ok(AgentDetail.from(agent, exposedToolNames())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 汇总注册表中的全部 Agent；注册表缺失时退化为仅主 Agent。 */
    private List<AgentView> agents() {
        AgentRegistry registry = agentRegistry.getIfAvailable();
        if (registry == null || registry.all().isEmpty()) {
            return List.of(new AgentView("main-agent", "Main Agent", "READY",
                    "规划、工具调用、记忆与审批编排入口", "planner", false, 0));
        }
        Set<String> exposed = exposedToolNames();
        return registry.all().stream().map(agent -> AgentView.from(agent, exposed)).toList();
    }

    /** 已作为工具注册的 Agent 标识集合，用于标记 Agent-as-Tool。 */
    private Set<String> exposedToolNames() {
        return toolRegistry.all().stream()
                .map(com.github.agentos.tool.api.AgentTool::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String provider(URI endpoint) {
        return endpoint == null || endpoint.getHost() == null ? "OpenAI compatible" : endpoint.getHost();
    }

    /** Console 运行时目录响应。 */
    public record CatalogResponse(
            List<AgentView> agents,
            List<ToolView> tools,
            List<SkillView> skills,
            List<McpServerView> mcpServers,
            List<ModelView> models,
            Map<String, Integer> limits) {
    }

    /** Agent 摘要。 */
    public record AgentView(
            String id, String name, String status, String description,
            String kind, boolean exposedAsTool, int subAgentCount) {

        static AgentView from(Agent agent, Set<String> exposedToolNames) {
            return new AgentView(
                    agent.id(), displayName(agent.id()), "READY", agent.description(),
                    kindOf(agent), exposedToolNames.contains(agent.id()), countSubAgents(agent));
        }
    }

    /** Agent 详情：在摘要之上补充子 Agent 与配置声明的工具。 */
    public record AgentDetail(
            String id, String name, String status, String description,
            String kind, boolean exposedAsTool, List<String> subAgents, List<String> tools) {

        static AgentDetail from(Agent agent, Set<String> exposedToolNames) {
            List<String> subAgents = agent instanceof BaseAgent base
                    ? base.subAgents().stream().map(BaseAgent::id).toList()
                    : List.of();
            List<String> tools = agent instanceof ConfigDrivenAgent driven
                    ? driven.definition().tools()
                    : List.of();
            return new AgentDetail(
                    agent.id(), displayName(agent.id()), "READY", agent.description(),
                    kindOf(agent), exposedToolNames.contains(agent.id()), subAgents, tools);
        }
    }

    /** 把 kebab-case 标识转为展示名，例如 {@code main-agent} → {@code Main Agent}。 */
    private static String displayName(String id) {
        String[] parts = id.split("[-_]");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.isEmpty() ? id : name.toString();
    }

    /** 推断 Agent 形态，供面板按类别分组展示。 */
    private static String kindOf(Agent agent) {
        if (agent instanceof ConfigDrivenAgent driven) {
            return driven.definition().kind();
        }
        if (agent instanceof BaseAgent base && !base.subAgents().isEmpty()) {
            return "workflow";
        }
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

    /** 工具定义摘要。 */
    public record ToolView(
            String name, String description, String riskLevel, int parameterCount,
            List<String> parameters) {
        static ToolView from(ToolDefinition definition) {
            return new ToolView(
                    definition.name(), definition.description(), definition.riskLevel().name(),
                    definition.parameters().size(),
                    definition.parameters().stream().map(parameter -> parameter.name()).toList());
        }
    }

    /** 技能摘要，正文不通过目录接口暴露。 */
    public record SkillView(String id, String name, String description, String source) {
        static SkillView from(AgentSkill skill) {
            return new SkillView(skill.id(), skill.name(), skill.description(), skill.source());
        }
    }

    /** MCP Server 配置摘要，启动命令仅暴露可执行程序名。 */
    public record McpServerView(String name, String status, String transport) {
    }

    /** 不含 API Key 和完整端点的模型摘要。 */
    public record ModelView(String role, String model, String provider, String workload) {
    }
}
