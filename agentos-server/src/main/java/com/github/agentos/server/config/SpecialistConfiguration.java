package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.loop.PlanExecuteAgent;
import com.github.agentos.agent.loop.ReactAgent;
import com.github.agentos.agent.specialist.CodeAgent;
import com.github.agentos.agent.specialist.ReportAgent;
import com.github.agentos.agent.specialist.SearchAgent;
import com.github.agentos.agent.specialist.SupervisorAgent;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import com.github.agentos.tool.builtin.shell.RunCommandTool;
import com.github.agentos.tool.builtin.web.BrowserSearchTool;
import com.github.agentos.tool.builtin.web.WebFetchTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专业 Agent 与监督路由层装配。
 *
 * <p>创建 SearchAgent、CodeAgent、ReportAgent 三个专业 Agent，
 * 并通过 {@link AgentToolAdapter} 暴露为工具（供 PlanExecuteAgent 规划器调用），
 * 同时装配 {@link SupervisorAgent} 作为 RoutingAgentLoop 的 fallback，
 * 形成路由层级：</p>
 *
 * <pre>
 * 问候短路 → SimpleQaAgent（单轮直答）
 *          → SupervisorAgent（单次 LLM 分类 + 直接派发）
 *                      → SearchAgent / CodeAgent / ReportAgent
 *                      → PlanExecuteAgent / ReactAgent（按任务选择规划执行或即时决策）
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class SpecialistConfiguration {

    /** 创建信息检索 Agent；browser_search 发现来源，web_fetch 读取来源正文。 */
    @Bean
    SearchAgent searchAgent(
            ChatClient chatClient,
            ObjectProvider<BrowserSearchTool> browserSearchProvider,
            ObjectProvider<WebFetchTool> webFetchProvider) {
        List<AgentTool> searchTools = new ArrayList<>();
        browserSearchProvider.ifAvailable(searchTools::add);
        return new SearchAgent(
                chatClient, List.copyOf(searchTools), webFetchProvider.getIfAvailable());
    }

    /** 创建代码编写与执行 Agent。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${agentos.security.allow-host-processes:false}' == 'true' && "
                    + "'${agentos.tools.run-command.enabled:false}' == 'true'")
    CodeAgent codeAgent(
            ChatClient chatClient,
            FileWriteTool fileWriteTool,
            RunCommandTool runCommandTool) {
        return new CodeAgent(chatClient, fileWriteTool, runCommandTool);
    }

    /** 创建文档生成 Agent。 */
    @Bean
    ReportAgent reportAgent(
            ChatClient chatClient,
            FileWriteTool fileWriteTool) {
        return new ReportAgent(chatClient, fileWriteTool);
    }

    /** 把 SearchAgent 暴露为工具，供 PlanExecuteAgent 规划器调用。 */
    @Bean
    AgentToolAdapter searchAgentTool(SearchAgent searchAgent, com.github.agentos.kernel.CheckpointStore checkpoints) {
        // 委派不预先审批，内部网络操作分别通过统一 Dispatcher 审批。
        return new AgentToolAdapter(searchAgent, AgentTool.RiskLevel.LOW, checkpoints);
    }

    /** 把 CodeAgent 暴露为工具，供 PlanExecuteAgent 规划器调用。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${agentos.security.allow-host-processes:false}' == 'true' && "
                    + "'${agentos.tools.run-command.enabled:false}' == 'true'")
    AgentToolAdapter codeAgentTool(CodeAgent codeAgent, com.github.agentos.kernel.CheckpointStore checkpoints) {
        return new AgentToolAdapter(codeAgent, AgentTool.RiskLevel.LOW, checkpoints);
    }

    /** 把 ReportAgent 暴露为工具，供 PlanExecuteAgent 规划器调用。 */
    @Bean
    AgentToolAdapter reportAgentTool(ReportAgent reportAgent, com.github.agentos.kernel.CheckpointStore checkpoints) {
        return new AgentToolAdapter(reportAgent, AgentTool.RiskLevel.LOW, checkpoints);
    }

    /**
     * 创建监督 Agent，作为 RoutingAgentLoop 的 fallback。
     *
     * <p>使用单次 LLM 调用完成任务分类；专业 Agent 的实际工具操作
     * 通过统一 Dispatcher 执行权限与审批检查。
     * PlanExecuteAgent 与 ReactAgent 始终参与路由，{@code agentos.agent.loop.mode}
     * 只决定分类失败、低置信度或专才拒单时的默认执行 Agent。</p>
     */
    @Bean
    SupervisorAgent supervisorAgent(
            ChatClient chatClient,
            SearchAgent searchAgent,
            ObjectProvider<CodeAgent> codeAgentProvider,
            ReportAgent reportAgent,
            PlanExecuteAgent planExecuteAgent,
            ReactAgent reactAgent,
            List<com.github.agentos.agent.specialist.ToolProviderAgent> toolGroups,
            @Value("${agentos.agent.loop.mode:react}") String defaultMode,
            @Value("${agentos.router.supervisor.min-confidence:0.75}") double minConfidence) {
        Map<String, Agent> specialists = new LinkedHashMap<>();
        specialists.put(SearchAgent.ID, searchAgent);
        specialists.put(ReportAgent.ID, reportAgent);
        codeAgentProvider.ifAvailable(codeAgent -> specialists.put(CodeAgent.ID, codeAgent));
        toolGroups.forEach(agent -> specialists.put(agent.id(), agent));
        String defaultAgentId = switch (defaultMode) {
            case "plan" -> "plan-execute-agent";
            case "react" -> ReactAgent.ID;
            default -> throw new IllegalArgumentException("Unsupported agentos.agent.loop.mode: " + defaultMode);
        };
        return new SupervisorAgent(chatClient, specialists,
                Map.of("plan-execute-agent", planExecuteAgent, ReactAgent.ID, reactAgent), defaultAgentId,
                new ObjectMapper(), minConfidence);
    }
}
