package com.github.agentos.server.config;

import com.github.agentos.agent.loop.ConversationCompactor;
import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.agent.loop.ReactAgent;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * React 模式装配：无显式计划、每轮即时决策的 Agent 循环。
 *
 * <p>始终创建 ReactAgent，与 PlanExecuteAgent 一起参与 Supervisor 路由。
 * {@code agentos.agent.loop.mode} 仅选择无法分类时的默认执行 Agent。</p>
 *
 * <p>子代理隔离复用既有装配：SearchAgent/CodeAgent/ReportAgent 经
 * {@code AgentToolAdapter} 已注册进 {@link ToolRegistry}，ReactAgent 的工具
 * 清单即注册表全量快照，模型可把大范围探索委托给子 Agent 工具，
 * 其中间产出隔离在子 Agent 上下文内，主循环只回收结论。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ReactLoopConfiguration {

    /**
     * 创建参与任务路由的 React Agent。
     *
     * @param chatClient 模型客户端
     * @param toolDispatcher 统一工具调度边界（保留 HITL 审批）
     * @param toolRegistry 工具注册表（含 AgentToolAdapter 包装的子 Agent）
     * @param defaults 通用运行预算；react 模式对模型调用数更敏感，单独放宽
     * @return React Agent
     */
    @Bean
    ReactAgent reactAgent(
            ChatClient chatClient,
            ToolDispatcher toolDispatcher,
            ToolRegistry toolRegistry,
            AgentExecutionLimits defaults,
            org.springframework.beans.factory.ObjectProvider<ContinuationStore> continuationStore,
            @Value("${agentos.agent.react.max-model-calls:24}") int maxModelCalls,
            @Value("${agentos.agent.react.max-tool-calls:20}") int maxToolCalls,
            @Value("${agentos.agent.react.max-context-chars:24000}") int maxContextChars,
            @Value("${agentos.agent.react.max-tool-result-chars:4000}") int maxToolResultChars,
            @Value("${agentos.agent.react.compressed-chars:600}") int compressedChars,
            @Value("${agentos.agent.react.reflection-interval:6}") int reflectionInterval,
            @Value("${agentos.agent.react.consecutive-failure-threshold:2}") int consecutiveFailureThreshold,
            @Value("${agentos.agent.react.allowed-tools:}") String allowedToolsCsv) {
        AgentExecutionLimits reactLimits = new AgentExecutionLimits(
                defaults.maxReplanCount(), defaults.maxStepCount(),
                maxToolCalls, maxModelCalls);
        // 白名单：配置非空时只下发指定工具，空则全量（向后兼容）。
        List<AgentTool> manifest = allowedToolsCsv == null || allowedToolsCsv.isBlank()
                ? toolRegistry.getTools(com.github.agentos.kernel.InvocationContext.of(ReactAgent.ID))
                : filterByAllowedTools(toolRegistry.getTools(com.github.agentos.kernel.InvocationContext.of(ReactAgent.ID)), allowedToolsCsv);
        ReactAgent agent = new ReactAgent(
                chatClient,
                toolDispatcher,
                manifest,
                reactLimits,
                new ConversationCompactor(maxContextChars, maxToolResultChars, compressedChars),
                continuationStore.getIfAvailable(() -> ContinuationStore.NOOP),
                reflectionInterval,
                consecutiveFailureThreshold);
        agent.configureToolProvider(context -> {
            List<AgentTool> available = toolRegistry.getTools(context);
            return allowedToolsCsv == null || allowedToolsCsv.isBlank()
                    ? available : filterByAllowedTools(available, allowedToolsCsv);
        });
        return agent;
    }

    /** 按白名单 CSV 过滤工具列表；不在白名单内的工具不下发给模型。 */
    private static List<AgentTool> filterByAllowedTools(
            List<AgentTool> all, String allowedToolsCsv) {
        Set<String> allowed = Arrays.stream(allowedToolsCsv.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());
        return all.stream()
                .filter(tool -> allowed.contains(tool.name()))
                .toList();
    }
}
