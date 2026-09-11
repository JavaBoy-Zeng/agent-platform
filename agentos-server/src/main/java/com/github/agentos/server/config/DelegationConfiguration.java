package com.github.agentos.server.config;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.agent.routing.*;
import com.github.agentos.agent.specialist.ToolProviderAgent;
import com.github.agentos.agent.workflow.*;
import com.github.agentos.kernel.*;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/** 将编排能力与实际工具隔离；Provider 在 Invocation 时解析，支持后注册的 MCP/Skill 工具。 */
@Configuration(proxyBeanMethods = false)
public class DelegationConfiguration {
    private static boolean workspaceTool(AgentTool tool) {
        return tool.name().startsWith("file_") || tool.name().startsWith("git_")
                || tool.name().equals("directory_list") || tool.name().equals("run_command");
    }

    @Bean ToolProviderAgent workspaceAgent(ChatClient chat, ObjectProvider<ToolRegistry> registry) {
        return new ToolProviderAgent("workspace-agent", "读取、搜索、修改工作区文件，运行命令与 Git 操作",
                "你是工作区操作专才。先读取相关文件再修改；分步验证操作结果。"
                        + "文件问题必须依据实际读取证据；有 hasMore 续读标记时继续读取。",
                Set.of(AgentCapability.CODE_WRITE, AgentCapability.COMMAND_EXECUTION,
                        AgentCapability.GENERAL_PLANNING, AgentCapability.DOCUMENT_GENERATION),
                Set.of(RouteScope.LOCAL_WORKSPACE, RouteScope.GENERAL),
                context -> registry.getObject().getTools(context).stream()
                        .filter(DelegationConfiguration::workspaceTool).toList(), chat);
    }

    @Bean ToolProviderAgent utilityAgent(ChatClient chat, ObjectProvider<ToolRegistry> registry) {
        return new ToolProviderAgent("utility-agent", "查询日期、天气、运行时目录及调用外部集成能力（MCP/Skill 适配工具）",
                "你是运行时与外部服务操作专才。按实际可用工具完成任务，不猜测实时信息。"
                        + "Skill 是操作指南，加载后遵循其中与任务相关的步骤，不能把指南当作执行结果。",
                Set.of(AgentCapability.GENERAL_PLANNING, AgentCapability.RUNTIME_CATALOG_READ,
                        AgentCapability.WEB_RESEARCH), Set.of(RouteScope.values()),
                context -> registry.getObject().getTools(context).stream()
                        .filter(tool -> !workspaceTool(tool))
                        .filter(tool -> !(tool instanceof AgentDelegationTool)).toList(), chat);
    }

    @Bean AgentToolAdapter workspaceAgentTool(ToolProviderAgent workspaceAgent, CheckpointStore checkpoints) {
        return new AgentToolAdapter(workspaceAgent, AgentTool.RiskLevel.LOW, checkpoints);
    }

    @Bean AgentToolAdapter utilityAgentTool(ToolProviderAgent utilityAgent, CheckpointStore checkpoints) {
        return new AgentToolAdapter(utilityAgent, AgentTool.RiskLevel.LOW, checkpoints);
    }

    @Bean SmartInitializingSingleton configureSpecialistExecution(List<ResumableSpecialist> specialists,
            ToolDispatcher dispatcher, ContinuationStore store, AgentExecutionLimits limits) {
        return () -> specialists.forEach(agent -> agent.configureExecution(dispatcher, store, limits));
    }
}
