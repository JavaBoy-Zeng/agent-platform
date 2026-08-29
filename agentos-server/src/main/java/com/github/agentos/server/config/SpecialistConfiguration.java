package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.loop.MainAgent;
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
import com.github.agentos.tool.builtin.web.WebSearchTool;
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
 * 并通过 {@link AgentToolAdapter} 暴露为工具（供 MainAgent 规划器调用），
 * 同时装配 {@link SupervisorAgent} 作为 RoutingAgentLoop 的 fallback，
 * 形成路由层级：</p>
 *
 * <pre>
 * 问候短路 → SimpleQaAgent（单轮直答）
 *          → SupervisorAgent（单次 LLM 分类 + 直接派发）
 *                      → SearchAgent / CodeAgent / ReportAgent
 *                      → MainAgent（多步规划 + 工具编排，含 AgentToolAdapter）
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class SpecialistConfiguration {

    /** 创建信息检索 Agent；browser_search 与 web_search 同时注册时并行使用两者的结果。 */
    @Bean
    SearchAgent searchAgent(
            ChatClient chatClient,
            ObjectProvider<BrowserSearchTool> browserSearchProvider,
            ObjectProvider<WebSearchTool> webSearchProvider) {
        List<AgentTool> searchTools = new ArrayList<>();
        BrowserSearchTool browserSearch = browserSearchProvider.getIfAvailable();
        if (browserSearch != null) {
            searchTools.add(browserSearch);
        }
        WebSearchTool webSearch = webSearchProvider.getIfAvailable();
        if (webSearch != null) {
            searchTools.add(webSearch);
        }
        return new SearchAgent(chatClient, List.copyOf(searchTools));
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

    /** 把 SearchAgent 暴露为工具，供 MainAgent 规划器调用。 */
    @Bean
    AgentToolAdapter searchAgentTool(SearchAgent searchAgent) {
        // 搜索本身保持 LOW：RISK_BASED 可直接放行；REQUEST_APPROVAL 仍会按工具名拦截联网。
        return new AgentToolAdapter(searchAgent, AgentTool.RiskLevel.LOW);
    }

    /** 把 CodeAgent 暴露为工具，供 MainAgent 规划器调用。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${agentos.security.allow-host-processes:false}' == 'true' && "
                    + "'${agentos.tools.run-command.enabled:false}' == 'true'")
    AgentToolAdapter codeAgentTool(CodeAgent codeAgent) {
        return new AgentToolAdapter(codeAgent, AgentTool.RiskLevel.HIGH);
    }

    /** 把 ReportAgent 暴露为工具，供 MainAgent 规划器调用。 */
    @Bean
    AgentToolAdapter reportAgentTool(ReportAgent reportAgent) {
        return new AgentToolAdapter(reportAgent, AgentTool.RiskLevel.HIGH);
    }

    /**
     * 创建监督 Agent，作为 RoutingAgentLoop 的 fallback。
     *
     * <p>使用单次 LLM 调用完成任务分类；FULL_ACCESS 可直接派发专业 Agent，
     * 其余权限模式与复杂任务回退到 MainAgent 的统一工具审批/续跑链路。
     * 当 {@code agentos.agent.loop.mode=react} 时，fallback 切换为
     * {@link ReactAgent}（无显式计划、每轮即时决策的 Tool-Calling 循环）。</p>
     */
    @Bean
    SupervisorAgent supervisorAgent(
            ChatClient chatClient,
            SearchAgent searchAgent,
            ObjectProvider<CodeAgent> codeAgentProvider,
            ReportAgent reportAgent,
            MainAgent mainAgent,
            ObjectProvider<ReactAgent> reactAgentProvider,
            @Value("${agentos.router.supervisor.min-confidence:0.75}") double minConfidence) {
        Map<String, Agent> specialists = new LinkedHashMap<>();
        specialists.put(SearchAgent.ID, searchAgent);
        specialists.put(ReportAgent.ID, reportAgent);
        codeAgentProvider.ifAvailable(codeAgent -> specialists.put(CodeAgent.ID, codeAgent));
        ReactAgent reactAgent = reactAgentProvider.getIfAvailable();
        com.github.agentos.kernel.AgentLoop fallback =
                reactAgent != null ? reactAgent : mainAgent;
        return new SupervisorAgent(
                chatClient, specialists, fallback, new ObjectMapper(), minConfidence);
    }
}
