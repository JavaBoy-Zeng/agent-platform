package com.github.agentos.server.controller;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.server.config.mcp.McpProperties;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.tool.api.ToolDefinition;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.skill.AgentSkill;
import com.github.agentos.tool.skill.SkillRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** 为 Console 管理面板提供不包含密钥的运行时只读目录。 */
@RestController
@RequestMapping("/api/console")
public final class ConsoleCatalogController {

    private final ToolRegistry toolRegistry;
    private final ObjectProvider<SkillRegistry> skillRegistry;
    private final ObjectProvider<McpProperties> mcpProperties;
    private final ModelClientProperties modelProperties;
    private final AgentExecutionLimits limits;

    /** 创建 Console 目录控制器。 */
    public ConsoleCatalogController(
            ToolRegistry toolRegistry,
            ObjectProvider<SkillRegistry> skillRegistry,
            ObjectProvider<McpProperties> mcpProperties,
            ModelClientProperties modelProperties,
            AgentExecutionLimits limits) {
        this.toolRegistry = toolRegistry;
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
                List.of(new AgentView("main-agent", "Main Agent", "READY",
                        "规划、工具调用、记忆与审批编排入口")),
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
    public record AgentView(String id, String name, String status, String description) {
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
