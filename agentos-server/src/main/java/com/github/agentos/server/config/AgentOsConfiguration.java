package com.github.agentos.server.config;

import com.github.agentos.agent.AgentFinalizer;
import com.github.agentos.agent.DefaultAgentFinalizer;
import com.github.agentos.agent.MainAgent;
import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.ApprovalToolInterceptor;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.InMemoryAgentEventPublisher;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.*;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.server.run.AgentRunCoordinator;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.builtin.file.reader.FileReaderFactory;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolInterceptor;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.builtin.file.access.AllowAllFileAccessPolicy;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.reader.DocxFileReader;
import com.github.agentos.tool.builtin.file.reader.PdfFileReader;
import com.github.agentos.tool.builtin.file.reader.TextFileReader;
import com.github.agentos.tool.builtin.EchoTool;
import com.github.agentos.tool.builtin.WeatherTool;
import com.github.agentos.tool.builtin.file.DirectoryListTool;
import com.github.agentos.tool.builtin.file.FileReadTool;
import com.github.agentos.tool.builtin.file.FileSearchTool;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import com.github.agentos.tool.builtin.git.GitCommitTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AgentOS 默认组件的 Spring 装配配置。
 *
 * <p>该配置集中组装工具、记忆、风险策略、计划执行器、主 Agent 和运行时。
 * 规划器由 {@link LlmPlannerConfiguration} 装配。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentOsConfiguration {

    /**
     * 创建 AgentOS 默认装配配置。
     */
    public AgentOsConfiguration() {
    }

    /**
     * 创建用于验证调用链的内置回显工具。
     *
     * @return 回显工具
     */
    @Bean
    EchoTool echoTool() {
        return new EchoTool();
    }

    @Bean
    WeatherTool weatherTool() {
        return new WeatherTool();
    }

    @Bean
    FileAccessPolicy fileAccessPolicy() {
        return new AllowAllFileAccessPolicy();
    }

    @Bean
    FileReadTool fileReadTool(FileAccessPolicy fileAccessPolicy) {
        return new FileReadTool(
                fileAccessPolicy,
                new FileReaderFactory(
                        List.of(
                                new PdfFileReader(),
                                new DocxFileReader(),
                                new TextFileReader()
                        )
                )
        );
    }

    @Bean
    DirectoryListTool directoryListTool(FileAccessPolicy fileAccessPolicy) {
        return new DirectoryListTool(fileAccessPolicy);
    }

    @Bean
    FileSearchTool fileSearchTool(FileAccessPolicy fileAccessPolicy) {
        return new FileSearchTool(fileAccessPolicy);
    }

    /** 创建需要 HITL 审批的 UTF-8 文件写入工具。 */
    @Bean
    FileWriteTool fileWriteTool(FileAccessPolicy fileAccessPolicy) {
        return new FileWriteTool(fileAccessPolicy);
    }

    /** 创建只提交明确路径且需要 HITL 审批的本地 Git 提交工具。 */
    @Bean
    GitCommitTool gitCommitTool() {
        return new GitCommitTool();
    }


    /**
     * 创建工具注册表，并注册 Spring 容器中的全部工具。
     *
     * @param tools 容器中已声明的 Agent 工具
     * @return 工具注册表
     */
    @Bean
    ToolRegistry toolRegistry(List<AgentTool> tools) {
        return new ToolRegistry(tools);
    }

    /** 创建将 HITL 审批纳入工具生命周期的前置拦截器。 */
    @Bean
    ToolInterceptor approvalToolInterceptor(
            RiskPolicy riskPolicy, ApprovalService approvalService) {
        return new ApprovalToolInterceptor(riskPolicy, approvalService);
    }

    /** 创建所有计划和未来执行策略共享的工具调度器。 */
    @Bean
    ToolDispatcher toolDispatcher(
            ToolRegistry toolRegistry, List<ToolInterceptor> toolInterceptors) {
        return new ToolDispatcher(toolRegistry, toolInterceptors);
    }

    /**
     * 创建统一记忆服务。
     *
     * @param mode          存储模式：{@code memory}、{@code file} 或 {@code sqlite}
     * @param dataDirectory 文件模式的数据目录
     * @param databaseFile  SQLite 模式的数据库文件
     * @return 记忆服务
     */
    @Bean(destroyMethod = "close")
    MemoryService memoryService(
            @Value("${agentos.memory.mode:file}") String mode,
            @Value("${agentos.memory.data-dir:.agentos/memory}") String dataDirectory,
            @Value("${agentos.memory.database-file:.agentos/memory/memory.sqlite}") String databaseFile) {
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "memory" -> MemoryService.inMemory();
            case "file" -> MemoryService.persistent(Path.of(dataDirectory));
            case "sqlite" -> MemoryService.sqlite(Path.of(databaseFile));
            default -> throw new IllegalArgumentException(
                    "agentos.memory.mode must be one of: memory, file, sqlite");
        };
    }

    /**
     * 创建默认风险策略，中风险及以上工具需要人工审批。
     *
     * @return 风险策略
     */
    @Bean
    RiskPolicy riskPolicy() {
        return new RiskPolicy(AgentTool.RiskLevel.MEDIUM);
    }

    /**
     * 创建默认人工审批服务。
     *
     * <p>在接入真实审批渠道前，默认处理器拒绝所有需要审批的操作。</p>
     *
     * @return 默认拒绝型人工审批服务
     */
    @Bean
    ApprovalService approvalService() {
        // 安全默认值：在接入真实 HITL 适配器前，所有风险操作都保持阻断。
        return new ApprovalService(request -> false);
    }

    /**
     * 创建模型计划校验器。
     *
     * @param toolRegistry 工具注册表
     * @return 使用默认步骤上限的计划校验器
     */
    @Bean
    PlanValidator planValidator(ToolRegistry toolRegistry) {
        return new PlanValidator(toolRegistry);
    }

    @Bean
    AgentExecutionLimits agentExecutionLimits(
            @Value("${agentos.runtime.max-replan-count:3}") int maxReplanCount,
            @Value("${agentos.runtime.max-step-count:30}") int maxStepCount,
            @Value("${agentos.runtime.max-tool-calls:30}") int maxToolCalls,
            @Value("${agentos.runtime.max-model-calls:6}") int maxModelCalls) {
        return new AgentExecutionLimits(
                maxReplanCount, maxStepCount, maxToolCalls, maxModelCalls);
    }

    @Bean
    FailureClassifier failureClassifier() {
        return new DefaultFailureClassifier();
    }

    @Bean
    ObservationSummarizer observationSummarizer(
            @Value("${agentos.runtime.max-observation-chars:4000}") int maxObservationChars,
            @Value("${agentos.runtime.max-observation-total-chars:24000}") int maxTotalChars) {
        return new DefaultObservationSummarizer(maxObservationChars, maxTotalChars);
    }

    /**
     * 创建计划执行器。
     *
     * @param toolRegistry    工具注册表
     * @param toolExecutor    工具执行器
     * @param riskPolicy      风险策略
     * @param approvalService 人工审批服务
     * @return 计划执行器
     */
    @Bean
    PlanExecutor planExecutor(
            ToolDispatcher toolDispatcher,
            FailureClassifier failureClassifier) {
        return new PlanExecutor(toolDispatcher, failureClassifier);
    }

    @Bean
    AgentFinalizer agentFinalizer() {
        return new DefaultAgentFinalizer();
    }

    /**
     * 创建默认主 Agent。
     *
     * @param agentPlanner   迭代式规划器
     * @param planExecutor   计划执行器
     * @param memoryService  记忆服务
     * @param agentFinalizer 内部最终回答收口器
     * @param limits         单次运行累计预算
     * @return 主 Agent
     */
    @Bean
    MainAgent mainAgent(
            AgentPlanner agentPlanner,
            PlanExecutor planExecutor,
            MemoryService memoryService,
            AgentFinalizer agentFinalizer,
            AgentExecutionLimits limits,
            ObservationSummarizer observationSummarizer) {
        return new MainAgent(
                agentPlanner, planExecutor, memoryService, agentFinalizer, limits,
                observationSummarizer);
    }

    /**
     * 创建面向 REST 接口的 Agent 运行时。
     *
     * @param mainAgent 主 Agent 循环
     * @return Agent 运行时
     */
    @Bean
    AgentRuntime agentRuntime(
            MainAgent mainAgent,
            AgentEventPublisher agentEventPublisher,
            AgentEventStore agentEventStore) {
        return new AgentRuntime(mainAgent, agentEventPublisher, agentEventStore);
    }

    /** 创建进程内领域事件发布器，后续可注册审计或遥测监听器。 */
    @Bean
    AgentEventPublisher agentEventPublisher() {
        return new InMemoryAgentEventPublisher();
    }

    /** 创建进程内领域事件存储，支持按 Invocation 和 Session 查询轨迹。 */
    @Bean
    AgentEventStore agentEventStore() {
        return new InMemoryAgentEventStore();
    }

    /**
     * 为 SSE Agent 运行创建轻量虚拟线程执行器。
     */
    @Bean(destroyMethod = "close")
    ExecutorService agentStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 注册流式运行及其真实执行线程，供停止接口协作取消。
     */
    @Bean
    AgentRunTaskRegistry agentRunTaskRegistry() {
        return new AgentRunTaskRegistry();
    }

    /** 创建支持刷新恢复和事件补播的后台运行协调器。 */
    @Bean
    AgentRunCoordinator agentRunCoordinator(
            AgentRuntime runtime,
            ExecutorService agentStreamExecutor,
            AgentRunTaskRegistry taskRegistry) {
        return new AgentRunCoordinator(runtime, agentStreamExecutor, taskRegistry);
    }
}
