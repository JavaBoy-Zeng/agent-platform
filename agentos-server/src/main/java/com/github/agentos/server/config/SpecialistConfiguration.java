package com.github.agentos.server.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.loop.MainAgent;
import com.github.agentos.agent.specialist.CodeAgent;
import com.github.agentos.agent.specialist.ReportAgent;
import com.github.agentos.agent.specialist.SearchAgent;
import com.github.agentos.agent.specialist.SupervisorAgent;
import com.github.agentos.agent.workflow.AgentToolAdapter;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import com.github.agentos.tool.builtin.shell.RunCommandTool;
import com.github.agentos.tool.builtin.web.WebFetchTool;
import com.github.agentos.tool.builtin.web.WebSearchTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
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

    /** 创建信息检索 Agent；web_search 不可用时退化为 web_fetch + LLM 直答。 */
    @Bean
    SearchAgent searchAgent(
            ChatClient chatClient,
            ObjectProvider<WebSearchTool> webSearchProvider,
            WebFetchTool webFetchTool) {
        return new SearchAgent(chatClient, webSearchProvider.getIfAvailable(), webFetchTool);
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
        return new AgentToolAdapter(searchAgent);
    }

    /** 把 CodeAgent 暴露为工具，供 MainAgent 规划器调用。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${agentos.security.allow-host-processes:false}' == 'true' && "
                    + "'${agentos.tools.run-command.enabled:false}' == 'true'")
    AgentToolAdapter codeAgentTool(CodeAgent codeAgent) {
        return new AgentToolAdapter(codeAgent);
    }

    /** 把 ReportAgent 暴露为工具，供 MainAgent 规划器调用。 */
    @Bean
    AgentToolAdapter reportAgentTool(ReportAgent reportAgent) {
        return new AgentToolAdapter(reportAgent);
    }

    /**
     * 创建监督 Agent，作为 RoutingAgentLoop 的 fallback。
     *
     * <p>使用单次 LLM 调用将任务分类到专业 Agent 直接执行，
     * 复杂任务回退到 MainAgent 走完整规划循环。</p>
     */
    @Bean
    SupervisorAgent supervisorAgent(
            ChatClient chatClient,
            SearchAgent searchAgent,
            ObjectProvider<CodeAgent> codeAgentProvider,
            ReportAgent reportAgent,
            MainAgent mainAgent) {
        Map<String, Agent> specialists = new LinkedHashMap<>();
        specialists.put(SearchAgent.ID, searchAgent);
        specialists.put(ReportAgent.ID, reportAgent);
        codeAgentProvider.ifAvailable(codeAgent -> specialists.put(CodeAgent.ID, codeAgent));
        return new SupervisorAgent(chatClient, specialists, mainAgent);
    }
}
