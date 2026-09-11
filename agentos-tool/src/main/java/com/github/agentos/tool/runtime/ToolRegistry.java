package com.github.agentos.tool.runtime;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolDefinition;
import com.github.agentos.tool.api.ToolProvider;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 以工具名称为键的线程安全注册表。
 *
 * <p>注册表拒绝同名工具重复注册，并向规划执行层提供查找、强制获取和枚举能力。
 * 同时实现 {@link ToolProvider}，作为内置工具集的统一来源接入运行时。</p>
 */
public final class ToolRegistry implements ToolProvider {

    private java.util.Set<String> orchestrators = java.util.Set.of();

    private final ConcurrentMap<String, AgentTool> tools = new ConcurrentHashMap<>();

    /**
     * 使用初始工具集合创建注册表。
     *
     * @param initialTools 启动时需要注册的工具集合
     * @throws NullPointerException 当工具集合为 {@code null} 时抛出
     * @throws IllegalArgumentException 当集合中存在同名工具时抛出
     */
    public ToolRegistry(Collection<? extends AgentTool> initialTools) {
        Objects.requireNonNull(initialTools, "initialTools must not be null").forEach(this::register);
    }

    /** 为指定编排 Agent 限定只允许 Agent 委派。 */
    public ToolRegistry(Collection<? extends AgentTool> tools, java.util.Set<String> orchestrators) {
        this(tools);
        this.orchestrators = java.util.Set.copyOf(orchestrators);
    }

    public boolean isOrchestrator(InvocationContext context) {
        return orchestrators.contains(context.agentId());
    }

    public boolean allows(InvocationContext context, AgentTool tool) {
        return !isOrchestrator(context) || tool instanceof com.github.agentos.tool.api.AgentDelegationTool;
    }

    public List<ToolDefinition> definitions(InvocationContext context) {
        return getTools(context).stream().map(ToolDefinition::from)
                .sorted(Comparator.comparing(ToolDefinition::name)).toList();
    }

    /**
     * 注册一个新工具。
     *
     * @param tool 待注册工具
     * @throws NullPointerException 当工具为 {@code null} 时抛出
     * @throws IllegalArgumentException 当同名工具已经存在时抛出
     */
    public void register(AgentTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        AgentTool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalArgumentException("tool already registered: " + tool.name());
        }
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 找到时返回包含工具的 {@link Optional}，否则返回空值
     */
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 按名称获取工具，工具不存在时直接报错。
     *
     * @param name 工具名称
     * @return 已注册的工具
     * @throws IllegalArgumentException 当工具不存在时抛出
     */
    public AgentTool require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("unknown tool: " + name));
    }

    /**
     * 获取当前已注册工具的只读快照。
     *
     * @return 工具列表
     */
    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    /** 注册表对任意 Invocation 作用域返回同一份内置工具集合。 */
    @Override
    public List<AgentTool> getTools(InvocationContext context) {
        return all().stream().filter(tool -> allows(context, tool)).toList();
    }

    /**
     * 获取供模型规划和计划校验使用的工具定义快照。
     *
     * <p>返回结果按工具名称排序，使模型提示和测试结果保持稳定。</p>
     *
     * @return 已注册工具的名称、说明、风险等级和参数结构
     */
    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(ToolDefinition::from)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }
}
