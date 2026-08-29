package com.github.agentos.agent.config;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.agent.workflow.LoopAgent;
import com.github.agentos.agent.workflow.ParallelAgent;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从 {@link AgentDefinition} 构建 Agent 实例。
 *
 * <p>支持四种类别：</p>
 * <ul>
 *   <li>{@code specialist} → {@link ConfigDrivenAgent}</li>
 *   <li>{@code sequential} → {@link ConfigSequentialAgent}</li>
 *   <li>{@code parallel} → {@link ParallelAgent}</li>
 *   <li>{@code loop} → {@link LoopAgent}</li>
 * </ul>
 *
 * <p>构建分两阶段：先构建 specialist（叶子），再构建 sequential/parallel/loop（组合），
 * 确保子 Agent 引用可以被解析。</p>
 */
public final class AgentFactory {

    private final AgentBuildContext context;

    public AgentFactory(AgentBuildContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    /**
     * 按声明顺序构建全部 Agent。
     *
     * <p>specialist 先构建（叶子节点），sequential/parallel/loop 后构建（组合节点），
     * 同一声明中的组合 Agent 可引用前面已构建的 specialist。</p>
     *
     * @param definitions Agent 定义列表
     * @return 按定义顺序排列的 Agent 实例列表
     */
    public List<Agent> buildAll(List<AgentDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Map<String, Agent> built = new HashMap<>();
        List<Agent> result = new ArrayList<>();

        // 第一阶段：构建 specialist
        for (AgentDefinition def : definitions) {
            if (def.isSpecialist()) {
                Agent agent = buildSpecialist(def);
                built.put(def.id(), agent);
                result.add(agent);
            }
        }

        // 第二阶段：构建 sequential / parallel / loop
        for (AgentDefinition def : definitions) {
            if (def.isSequential()) {
                Agent agent = buildSequential(def, built);
                built.put(def.id(), agent);
                result.add(agent);
            } else if (def.isParallel()) {
                Agent agent = buildParallel(def, built);
                built.put(def.id(), agent);
                result.add(agent);
            } else if (def.isLoop()) {
                Agent agent = buildLoop(def, built);
                built.put(def.id(), agent);
                result.add(agent);
            }
        }

        return List.copyOf(result);
    }

    /** 构建 specialist 类别的 Agent。 */
    private Agent buildSpecialist(AgentDefinition def) {
        List<AgentTool> tools = new ArrayList<>();
        for (String toolName : def.tools()) {
            context.findTool(toolName).ifPresentOrElse(
                    tools::add,
                    () -> { /* 工具不存在则跳过（容错） */ });
        }
        return new ConfigDrivenAgent(def, context.chatClient(), tools);
    }

    /** 解析子 Agent 引用列表（sequential/parallel/loop 共用）。 */
    private List<BaseAgent> resolveSubAgents(AgentDefinition def, Map<String, Agent> built) {
        List<BaseAgent> subAgents = new ArrayList<>();
        for (String subId : def.subAgents()) {
            Agent child = built.get(subId);
            if (child == null) {
                throw new IllegalStateException(
                        "subAgent not found or not yet built: " + subId
                                + " (id=" + def.id() + ")");
            }
            if (!(child instanceof BaseAgent baseChild)) {
                throw new IllegalStateException(
                        "subAgent must be a BaseAgent: " + subId
                                + " (actual: " + child.getClass().getName() + ")");
            }
            subAgents.add(baseChild);
        }
        return subAgents;
    }

    /** 构建 sequential 类别的 Agent。 */
    private Agent buildSequential(AgentDefinition def, Map<String, Agent> built) {
        List<BaseAgent> subAgents = resolveSubAgents(def, built);
        return new ConfigSequentialAgent(def, subAgents);
    }

    /** 构建 parallel 类别的 Agent——并行执行全部子 Agent 并合并结果。 */
    private Agent buildParallel(AgentDefinition def, Map<String, Agent> built) {
        List<BaseAgent> subAgents = resolveSubAgents(def, built);
        return new ConfigComposedAgent(def, new ParallelAgent(def.id(), def.description(), subAgents));
    }

    /** 构建 loop 类别的 Agent——循环执行子 Agent 直到显式终止或达到迭代上限。 */
    private Agent buildLoop(AgentDefinition def, Map<String, Agent> built) {
        List<BaseAgent> subAgents = resolveSubAgents(def, built);
        return new ConfigComposedAgent(def, new LoopAgent(def.id(), def.description(), subAgents, def.maxIterations()));
    }

    /**
     * 配置驱动的串行编排 Agent。
     *
     * <p>按声明顺序依次执行子 Agent；任一子 Agent 进入 FAILED / CANCELLED / WAITING
     * 终态时立即短路返回；全部成功后以最后一个非空输出完成。</p>
     */
    private static final class ConfigSequentialAgent extends BaseAgent implements Agent {

        ConfigSequentialAgent(AgentDefinition def, List<BaseAgent> subAgents) {
            super(def.id(), def.description(), subAgents);
        }

        @Override
        public AgentExecutionResult run(AgentRequest request,
                                        InvocationContext context) {
            AgentState state = run(request, context, AgentState.ready().startNextIteration(),
                    AgentEventSink.NOOP);
            return AgentExecutionResult.from(
                    state, context.invocation() == null ? null : context.invocation().pendingAction());
        }

        @Override
        public AgentState run(
                AgentRequest request,
                InvocationContext context,
                AgentState runningState,
                AgentEventSink eventSink) {
            AgentState state = runningState;
            String lastOutput = "";
            for (BaseAgent child : subAgents()) {
                state = runChild(child, request, context, state, eventSink);
                if (state.status() != AgentState.Status.COMPLETED) {
                    return state;
                }
                if (!state.output().isBlank()) {
                    lastOutput = state.output();
                }
            }
            return state.complete(lastOutput);
        }
    }

    /**
     * 配置驱动的组合编排 Agent（parallel / loop）。
     *
     * <p>委托给 {@link ParallelAgent} 或 {@link LoopAgent} 执行，自身只实现
     * {@link Agent} 接口的 {@code run(request, context)} 适配方法，
     * 让编排原语能被注册到 {@code AgentRegistry} 并经 {@link com.github.agentos.agent.workflow.AgentToolAdapter}
     * 暴露为工具。</p>
     */
    private static final class ConfigComposedAgent extends BaseAgent implements Agent {
        private final BaseAgent delegate;

        ConfigComposedAgent(AgentDefinition def, BaseAgent delegate) {
            super(def.id(), def.description(), delegate.subAgents());
            this.delegate = delegate;
        }

        @Override
        public AgentExecutionResult run(AgentRequest request,
                                        InvocationContext context) {
            AgentState state = delegate.run(request, context,
                    AgentState.ready().startNextIteration(), AgentEventSink.NOOP);
            return AgentExecutionResult.from(
                    state, context.invocation() == null ? null : context.invocation().pendingAction());
        }

        @Override
        public AgentState run(
                AgentRequest request,
                InvocationContext context,
                AgentState runningState,
                AgentEventSink eventSink) {
            return delegate.run(request, context, runningState, eventSink);
        }
    }
}
