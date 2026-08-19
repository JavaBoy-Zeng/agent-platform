package com.github.agentos.server.config;

import com.github.agentos.agent.finalize.AgentFinalizer;
import com.github.agentos.agent.finalize.DefaultAgentFinalizer;
import com.github.agentos.agent.loop.MainAgent;
import com.github.agentos.agent.routing.RoutingAgentLoop;
import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.ApprovalToolInterceptor;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentEventPublisher;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.CheckpointStore;
import com.github.agentos.kernel.InMemoryAgentEventPublisher;
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
import com.github.agentos.tool.builtin.TodayTool;
import com.github.agentos.tool.builtin.WeatherTool;
import com.github.agentos.tool.builtin.file.DirectoryListTool;
import com.github.agentos.tool.builtin.file.FileReadTool;
import com.github.agentos.tool.builtin.file.FileSearchTool;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import com.github.agentos.tool.builtin.git.GitCommitTool;
import com.github.agentos.tool.builtin.shell.RunCommandTool;
import com.github.agentos.tool.builtin.web.WebFetchTool;
import com.github.agentos.tool.builtin.web.WebSearchTool;
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

    /** 创建返回当前日期（年月日+星期）的内置工具。 */
    @Bean
    TodayTool todayTool() {
        return new TodayTool();
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
     * 创建受限工作目录内的 shell 执行工具。
     *
     * <p>整体 HIGH 风险；只读命令（ls/grep/mvn test 等）由
     * {@link com.github.agentos.hitl.CommandRiskPolicy} 免审批放行，
     * 其余命令仍需人工审批。可用 {@code agentos.tools.run-command.enabled=false} 关闭。</p>
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentos.tools.run-command.enabled", havingValue = "true", matchIfMissing = true)
    RunCommandTool runCommandTool(
            @Value("${agentos.tools.run-command.work-dir:${user.dir}}") String workDir,
            @Value("${agentos.tools.run-command.timeout-seconds:60}") long timeoutSeconds,
            @Value("${agentos.tools.run-command.max-output-chars:20000}") int maxOutputChars) {
        return new RunCommandTool(Path.of(workDir), timeoutSeconds, maxOutputChars);
    }


    /** 创建网页抓取工具，限制响应大小并转为纯文本。 */
    @Bean
    WebFetchTool webFetchTool(
            @Value("${agentos.tools.web-fetch.timeout-seconds:20}") long timeoutSeconds,
            @Value("${agentos.tools.web-fetch.max-chars:12000}") int maxChars) {
        return new WebFetchTool(
                java.net.http.HttpClient.newBuilder().followRedirects(
                        java.net.http.HttpClient.Redirect.NORMAL).build(),
                java.time.Duration.ofSeconds(timeoutSeconds), maxChars);
    }

    /** 仅在配置了搜索 API Key 时注册 Tavily 网页搜索工具。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "!'${agentos.tools.web-search.api-key:}'.isBlank()")
    WebSearchTool webSearchTool(
            tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${agentos.tools.web-search.endpoint:https://api.tavily.com/search}") String endpoint,
            @Value("${agentos.tools.web-search.api-key:}") String apiKey,
            @Value("${agentos.tools.web-search.timeout-seconds:20}") long timeoutSeconds) {
        return new WebSearchTool(
                java.net.http.HttpClient.newHttpClient(), objectMapper, endpoint,
                apiKey, java.time.Duration.ofSeconds(timeoutSeconds));
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
     * 创建内容级风险策略，中风险及以上工具需要人工审批。
     *
     * <p>{@code run_command} 按命令文本判定：只读白名单命令免审批，
     * 其余命令一律审批；其他工具仍按声明等级判定。</p>
     *
     * @return 风险策略
     */
    @Bean
    RiskPolicy riskPolicy() {
        return new com.github.agentos.hitl.CommandRiskPolicy(AgentTool.RiskLevel.MEDIUM);
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
     * @param continuationStore 断点续跑状态存储；sqlite 模式下重启后审批仍可恢复
     * @return 主 Agent
     */
    @Bean
    MainAgent mainAgent(
            AgentPlanner agentPlanner,
            PlanExecutor planExecutor,
            MemoryService memoryService,
            AgentFinalizer agentFinalizer,
            AgentExecutionLimits limits,
            ObservationSummarizer observationSummarizer,
            com.github.agentos.agent.loop.ContinuationStore continuationStore) {
        return new MainAgent(
                agentPlanner, planExecutor, memoryService, agentFinalizer, limits,
                observationSummarizer, continuationStore);
    }

    /**
     * 创建本地文件系统产物存储。
     *
     * <p>工具写入的文件在执行边界自动登记为会话产物，
     * 可经 {@code /api/artifacts} 查询与下载。</p>
     *
     * @param root 产物存储根目录
     * @return 本地产物存储
     */
    @Bean
    com.github.agentos.kernel.LocalArtifactService localArtifactService(
            @Value("${agentos.artifacts.root:.agentos/artifacts}") String root) {
        return new com.github.agentos.kernel.LocalArtifactService(Path.of(root));
    }

    /**
     * 创建面向 REST 接口的 Agent 运行器。
     *
     * @param routingAgentLoop 意图路由 Agent 循环；具体行为见 {@link RoutingAgentLoop}
     * @param checkpointStore 审批恢复 Checkpoint 存储
     * @param agentExecutionLimits 单次运行累计执行预算
     * @param pluginManager 横切能力插件集合（记账、追踪等）
     * @param artifactService 会话产物存储
     * @return Agent 运行器
     */
    @Bean
    AgentRunner agentRunner(
            RoutingAgentLoop routingAgentLoop,
            AgentEventPublisher agentEventPublisher,
            AgentEventStore agentEventStore,
            CheckpointStore checkpointStore,
            com.github.agentos.kernel.SessionService sessionService,
            AgentExecutionLimits agentExecutionLimits,
            com.github.agentos.kernel.AgentPluginManager pluginManager,
            com.github.agentos.kernel.ArtifactService artifactService) {
        return new AgentRunner(
                routingAgentLoop, agentEventPublisher, agentEventStore, checkpointStore,
                sessionService, agentExecutionLimits, pluginManager, artifactService);
    }

    /**
     * 装配横切能力插件集合。
     *
     * <p>容器内全部 {@link com.github.agentos.kernel.AgentPlugin} 按依赖顺序接入，
     * 在 Runner 执行边界与用量回调点统一分发。</p>
     */
    @Bean
    com.github.agentos.kernel.AgentPluginManager agentPluginManager(
            java.util.List<com.github.agentos.kernel.AgentPlugin> plugins) {
        return com.github.agentos.kernel.AgentPluginManager.of(plugins);
    }

    /**
     * 把插件集合适配为模型用量监听器，供两个模型客户端注入。
     *
     * <p>客户端回调经 {@code AgentPluginManager} 分发到记账等插件，
     * 单个插件异常被隔离，不影响模型调用链。</p>
     */
    @Bean
    com.github.agentos.planner.ModelUsageListener modelUsageListener(
            com.github.agentos.kernel.AgentPluginManager pluginManager) {
        return pluginManager::onModelUsage;
    }

    /** 创建进程内领域事件发布器，后续可注册审计或遥测监听器。 */
    @Bean
    AgentEventPublisher agentEventPublisher() {
        return new InMemoryAgentEventPublisher();
    }

    /**
     * 创建会话多轮历史服务，供控制器在运行前把最近轮次注入请求属性。
     *
     * @param agentEventStore 领域事件存储
     * @param maxTurns 最多保留的完整轮次数
     * @param maxMessageChars 单条消息截断上限
     * @return 会话历史服务
     */
    @Bean
    com.github.agentos.server.history.SessionHistoryService sessionHistoryService(
            AgentEventStore agentEventStore,
            @Value("${agentos.history.max-turns:5}") int maxTurns,
            @Value("${agentos.history.max-message-chars:400}") int maxMessageChars) {
        return new com.github.agentos.server.history.SessionHistoryService(
                agentEventStore, maxTurns, maxMessageChars);
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
            AgentRunner runner,
            ExecutorService agentStreamExecutor,
            AgentRunTaskRegistry taskRegistry) {
        return new AgentRunCoordinator(runner, agentStreamExecutor, taskRegistry);
    }
}
