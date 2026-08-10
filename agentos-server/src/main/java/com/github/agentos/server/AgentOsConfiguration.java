package com.github.agentos.server;

import com.github.agentos.agent.MainAgent;
import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.PlanValidator;
import com.github.agentos.planner.TaskPlanner;
import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.EchoTool;
import com.github.agentos.tool.ToolExecutor;
import com.github.agentos.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * AgentOS 默认组件的 Spring 装配配置。
 *
 * <p>该配置集中组装工具、记忆、风险策略、计划执行器、主 Agent 和运行时。
 * 规划器按照运行环境分别由 {@link LlmPlannerConfiguration} 和
 * {@link DemoPlannerConfiguration} 装配，领域模块本身不依赖 Spring。</p>
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

    /**
     * 创建工具执行器。
     *
     * @param toolRegistry 工具注册表
     * @return 工具执行器
     */
    @Bean
    ToolExecutor toolExecutor(ToolRegistry toolRegistry) {
        return new ToolExecutor(toolRegistry);
    }

    /**
     * 创建每个会话最多保留二十条记录的短期记忆。
     *
     * @return 短期记忆存储
     */
    /**
     * 创建进程内长期记忆存储。
     *
     * @return 长期记忆存储
     */
    /**
     * 创建统一记忆服务。
     *
     * @param shortMemory 短期记忆存储
     * @param longMemory  长期记忆存储
     * @return 记忆服务
     */
    @Bean(destroyMethod = "close")
    MemoryService memoryService(
            @Value("${agentos.memory.mode:file}") String mode,
            @Value("${agentos.memory.data-dir:.agentos/memory}") String dataDirectory) {
        return "memory".equalsIgnoreCase(mode)
                ? MemoryService.inMemory()
                : MemoryService.persistent(Path.of(dataDirectory));
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
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            RiskPolicy riskPolicy,
            ApprovalService approvalService) {
        return new PlanExecutor(toolRegistry, toolExecutor, riskPolicy, approvalService);
    }

    /**
     * 创建默认主 Agent。
     *
     * @param taskPlanner   任务规划器
     * @param planExecutor  计划执行器
     * @param memoryService 记忆服务
     * @return 主 Agent
     */
    @Bean
    MainAgent mainAgent(
            TaskPlanner taskPlanner,
            PlanExecutor planExecutor,
            MemoryService memoryService) {
        return new MainAgent(taskPlanner, planExecutor, memoryService);
    }

    /**
     * 创建面向 REST 接口的 Agent 运行时。
     *
     * @param mainAgent 主 Agent 循环
     * @return Agent 运行时
     */
    @Bean
    AgentRuntime agentRuntime(MainAgent mainAgent) {
        return new AgentRuntime(mainAgent);
    }
}
